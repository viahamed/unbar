/*
 ============================================================================
 Name        : hev-socks5-tunnel.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2025 hev
 Description : Socks5 Tunnel
 ============================================================================
 */

#include <errno.h>
#include <assert.h>
#include <signal.h>
#include <string.h>
#include <stdatomic.h>
#include <hev-sys.h>

#ifdef __GLIBC__
#include <malloc.h>
#endif

#include <lwip/tcp.h>
#include <lwip/udp.h>
#include <lwip/nd6.h>
#include <lwip/netif.h>
#include <lwip/ip4_frag.h>
#include <lwip/ip6_frag.h>
#include <lwip/priv/tcp_priv.h>

#include <hev-task.h>
#include <hev-task-io.h>
#include <hev-task-io-socket.h>
#include <hev-task-mutex.h>
#include <hev-task-system.h>
#include <hev-memory-allocator.h>

#include "hev-exec.h"
#include "hev-config.h"
#include "hev-utils.h"
#include "hev-logger.h"
#include "hev-compat.h"
#include "hev-tunnel.h"
#include "hev-compiler.h"
#include "hev-mapped-dns.h"
#include "hev-config-const.h"
#include "hev-socks5-session-tcp.h"
#include "hev-socks5-session-udp.h"

#include "hev-socks5-tunnel.h"

enum
{
    SYNC_SEND = 1 << 0,
    SYNC_SENT = 1 << 1,
    SYNC_WAIT = 1 << 2,
    SYNC_STOP = 1 << 3,
};

static int run;
static atomic_int tsync;

static int tun_fd = -1;
static int tun_fd_local;
static int session_count;
static int evicted_count;
static int event_fds[2] = { -1, -1 };

static size_t stat_tx_packets;
static size_t stat_rx_packets;
static size_t stat_tx_bytes;
static size_t stat_rx_bytes;

/*
 * Local Unbar addition (1.0.13): answer QUIC and non-DNS UDP with ICMP
 * Port Unreachable so apps fall back to TCP immediately instead of
 * waiting out a UDP timeout. Both rejections are ON by default (1.0.37:
 * non-DNS UDP was OFF until 1.0.36, which left WhatsApp calls, VoIP and
 * any other non-QUIC UDP hanging on a timeout — the iOS tunstack already
 * rejects all non-DNS UDP). Both flags are reset to these defaults in
 * hev_socks5_tunnel_fini.
 */
static int reject_quic = 1;
static int reject_non_dns_udp = 1;

static struct netif *netif;
static struct tcp_pcb *tcp;
static struct udp_pcb *udp;

static HevTaskMutex mutex;
static HevTask *task_event;
static HevTask *task_lwip_io;
static HevTask *task_lwip_timer;
static HevList session_set;

static int
task_io_yielder (HevTaskYieldType type, void *data)
{
    hev_task_yield (type);

    return READ_ONCE (run) ? 0 : -1;
}

/* Forward declaration: defined below; used by the ICMP reply builder. */
static err_t netif_output_handler (struct netif *netif, struct pbuf *p);

/*
 * Local Unbar addition (1.0.13): Internet checksum (RFC 1071) over a
 * byte buffer, for the ICMP reply built below.
 */
static uint16_t
tunnel_inet_checksum (const uint8_t *data, size_t len)
{
    uint32_t sum = 0;

    while (len > 1) {
        sum += (data[0] << 8) | data[1];
        data += 2;
        len -= 2;
    }
    if (len)
        sum += data[0] << 8;

    while (sum >> 16)
        sum = (sum & 0xffff) + (sum >> 16);

    return (uint16_t) ~sum;
}

/*
 * Local Unbar addition (1.0.13): destination UDP port of an IPv4/UDP
 * or IPv6/UDP packet, or 0 when the packet is not UDP or is too short
 * to hold a full IP header plus a UDP header. The IP header length (in
 * bytes) is returned through ip_hlen when non-NULL.
 * (1.0.37): IPv6 was missing — an IPv6 packet returned 0, so IPv6 QUIC
 * (Instagram, YouTube, ...) slipped past the reject below, reached the
 * Go core and was silently dropped, and the app waited out a QUIC
 * timeout. IPv6 has a fixed 40-byte header; the next-header byte is at
 * [6] and the UDP destination port at [42]. Extension headers are not
 * followed — a packet with extension headers is not rejected here.
 */
static int
tunnel_udp_dst_port (const uint8_t *data, size_t len, unsigned int *ip_hlen)
{
    unsigned int ihl;

    if (len < 28)
        return 0;
    if ((data[0] >> 4) == 6) {
        /* IPv6: 40-byte header, next header at [6]. */
        if (len < 40 + 8)
            return 0;
        if (data[6] != IP_PROTO_UDP)
            return 0;

        if (ip_hlen)
            *ip_hlen = 40;

        return (data[42] << 8) | data[43];
    }
    if ((data[0] >> 4) != 4)
        return 0;
    if (data[9] != IP_PROTO_UDP)
        return 0;

    ihl = (data[0] & 0x0f) * 4;
    if ((ihl < 20) || (len < (size_t)ihl + 8))
        return 0;

    if (ip_hlen)
        *ip_hlen = ihl;

    return (data[ihl + 2] << 8) | data[ihl + 3];
}

/*
 * Local Unbar addition (1.0.13): build and send an ICMPv4 Destination
 * Unreachable / Port Unreachable reply (type 3, code 3, RFC 792) for
 * an IPv4/UDP packet, back through the TUN. The reply carries the
 * original IP header plus the first 8 bytes of the original datagram.
 * The packet is sent with netif_output_handler, so it counts toward
 * the RX statistics like any other packet leaving the tunnel.
 */
/*
 * Local Unbar addition (1.0.37): ICMPv6 Port Unreachable for a rejected
 * IPv6/UDP packet (type 1, code 4). The 1.0.13 reject was IPv4-only, so
 * IPv6 QUIC (Instagram, YouTube, ...) slipped through, reached the Go
 * core and was silently dropped, and the app waited out a QUIC timeout.
 * The ICMPv6 checksum covers the pseudo-header (source + destination +
 * length + next header) plus the ICMPv6 message (RFC 2460), computed
 * with tunnel_inet_checksum. The packet is written straight to the TUN,
 * like the IPv4 version.
 */
static void
tunnel_send_icmp6_port_unreachable (const uint8_t *data, size_t len)
{
    /* IPv6 header (40) + ICMPv6 header (8) + original IPv6 header (40)
     * + first 8 bytes of the UDP datagram. */
    uint8_t pkt[40 + 8 + 40 + 8];
    /* Pseudo-header (40) + ICMPv6 message (8 + 40 + 8). */
    uint8_t csum_buf[40 + 8 + 40 + 8];
    uint16_t icmp_len;
    uint16_t csum;
    struct pbuf *p;

    if (len < 40 + 8)
        return;

    icmp_len = (uint16_t) (8 + 40 + 8);

    /* IPv6 header (40 bytes). */
    pkt[0] = 0x60; /* version 6, traffic class 0, flow label 0 */
    pkt[1] = 0;
    pkt[2] = 0;
    pkt[3] = 0;
    pkt[4] = (icmp_len >> 8) & 0xff; /* payload length */
    pkt[5] = icmp_len & 0xff;
    pkt[6] = 58; /* next header: ICMPv6 */
    pkt[7] = 64; /* hop limit */
    /* Source = original destination, destination = original source. */
    memcpy (&pkt[8], &data[24], 16);
    memcpy (&pkt[24], &data[8], 16);

    /* ICMPv6 header (8 bytes): type 1, code 4 (port unreachable). */
    pkt[40] = 1;
    pkt[41] = 4;
    pkt[42] = 0; /* checksum, filled below */
    pkt[43] = 0;
    pkt[44] = 0; /* unused */
    pkt[45] = 0;
    pkt[46] = 0;
    pkt[47] = 0;
    /* Body: original IPv6 header + first 8 bytes of the UDP datagram. */
    memcpy (&pkt[48], data, 40 + 8);

    /* ICMPv6 checksum over the pseudo-header + ICMPv6 message. */
    memcpy (&csum_buf[0], &pkt[8], 16);   /* source */
    memcpy (&csum_buf[16], &pkt[24], 16); /* destination */
    csum_buf[32] = 0;
    csum_buf[33] = 0;
    csum_buf[34] = (icmp_len >> 8) & 0xff; /* upper-layer length */
    csum_buf[35] = icmp_len & 0xff;
    csum_buf[36] = 0;
    csum_buf[37] = 0;
    csum_buf[38] = 0;
    csum_buf[39] = 58; /* next header: ICMPv6 */
    memcpy (&csum_buf[40], &pkt[40], icmp_len);
    csum = tunnel_inet_checksum (csum_buf, sizeof (csum_buf));
    pkt[42] = (csum >> 8) & 0xff;
    pkt[43] = csum & 0xff;

    p = pbuf_alloc (PBUF_RAW, sizeof (pkt), PBUF_RAM);
    if (!p)
        return;

    memcpy (p->payload, pkt, sizeof (pkt));
    netif_output_handler (netif, p);
    pbuf_free (p);
}

static void
tunnel_send_icmp_port_unreachable (const uint8_t *data, size_t len,
                                   unsigned int ip_hlen)
{
    uint8_t pkt[20 + 8 + 60 + 8];
    uint16_t total_len;
    uint16_t csum;
    struct pbuf *p;

    /* (1.0.37): IPv6 packets get an ICMPv6 Port Unreachable (above);
     * the code below builds an IPv4 ICMP packet. */
    if ((data[0] >> 4) == 6) {
        tunnel_send_icmp6_port_unreachable (data, len);
        return;
    }

    if (len < (size_t)ip_hlen + 8)
        return;

    total_len = (uint16_t) (20 + 8 + ip_hlen + 8);

    /* IPv4 header (20 bytes). */
    pkt[0] = 0x45; /* version 4, IHL 5 */
    pkt[1] = 0;    /* TOS */
    pkt[2] = (total_len >> 8) & 0xff;
    pkt[3] = total_len & 0xff;
    pkt[4] = 0; /* ID */
    pkt[5] = 0;
    pkt[6] = 0; /* flags + fragment offset */
    pkt[7] = 0;
    pkt[8] = 64; /* TTL */
    pkt[9] = IP_PROTO_ICMP;
    pkt[10] = 0; /* checksum, filled below */
    pkt[11] = 0;
    /* Source = original destination, destination = original source. */
    memcpy (&pkt[12], &data[16], 4);
    memcpy (&pkt[16], &data[12], 4);
    csum = tunnel_inet_checksum (pkt, 20);
    pkt[10] = (csum >> 8) & 0xff;
    pkt[11] = csum & 0xff;

    /* ICMP header (8 bytes): type 3, code 3 (port unreachable). */
    pkt[20] = 3;
    pkt[21] = 3;
    pkt[22] = 0; /* checksum, filled below */
    pkt[23] = 0;
    pkt[24] = 0; /* unused */
    pkt[25] = 0;
    pkt[26] = 0;
    pkt[27] = 0;
    /* Body: original IP header + first 8 bytes of the UDP datagram. */
    memcpy (&pkt[28], data, ip_hlen + 8);
    csum = tunnel_inet_checksum (&pkt[20], (size_t) (8 + ip_hlen + 8));
    pkt[22] = (csum >> 8) & 0xff;
    pkt[23] = csum & 0xff;

    p = pbuf_alloc (PBUF_RAW, total_len, PBUF_RAM);
    if (!p)
        return;

    memcpy (p->payload, pkt, total_len);
    netif_output_handler (netif, p);
    pbuf_free (p);
}

static err_t
netif_output_handler (struct netif *netif, struct pbuf *p)
{
    ssize_t s;

    s = hev_tunnel_write (tun_fd, p);
    if (s <= 0) {
        if (errno == EAGAIN)
            return ERR_WOULDBLOCK;
        /* Large packets may not fit, lwIP sends smaller ones then. */
        if ((errno == ENOBUFS) || (errno == ENOMEM))
            return ERR_MEM;
        LOG_W ("socks5 tunnel write");
        return ERR_IF;
    }

    stat_rx_packets++;
    stat_rx_bytes += s;

    return ERR_OK;
}

static err_t
netif_output_v4_handler (struct netif *netif, struct pbuf *p,
                         const ip4_addr_t *ipaddr)
{
    return netif_output_handler (netif, p);
}

static err_t
netif_output_v6_handler (struct netif *netif, struct pbuf *p,
                         const ip6_addr_t *ipaddr)
{
    return netif_output_handler (netif, p);
}

static err_t
netif_init_handler (struct netif *netif)
{
    netif->output = netif_output_v4_handler;
    netif->output_ip6 = netif_output_v6_handler;

    return ERR_OK;
}

static inline void
hev_socks5_tunnel_mark_evicted (HevListNode *node)
{
    node->next = node->prev = node;
}

static inline int
hev_socks5_tunnel_is_evicted (HevListNode *node)
{
    return node->next == node;
}

/*
 * Over max-session-count the least recently active session is evicted. It is
 * taken off the set right away: a terminated session lingers until its task
 * runs again, and a burst of accepts handled in one go would otherwise
 * terminate that same head over and over while the set keeps growing (iOS NE
 * jetsam, 30.10: 190 sessions at a cap of 130).
 */
static void
hev_socks5_tunnel_evict_session (HevListNode *node)
{
    HevSocks5SessionData *sd;

    sd = container_of (node, HevSocks5SessionData, node);
    hev_list_del (&session_set, node);
    hev_socks5_tunnel_mark_evicted (node);
    session_count--;
    evicted_count++;
    hev_socks5_session_terminate (sd->self);
}

static void
hev_socks5_tunnel_insert_session (HevListNode *node)
{
    int max_session_count;

    hev_list_add_tail (&session_set, node);
    session_count++;

    max_session_count = hev_config_get_misc_max_session_count ();
    if (!max_session_count)
        return;

    while (session_count >= max_session_count)
        hev_socks5_tunnel_evict_session (hev_list_first (&session_set));
}

static void
hev_socks5_tunnel_delete_session (HevListNode *node)
{
    if (hev_socks5_tunnel_is_evicted (node)) {
        evicted_count--;
        return;
    }

    hev_list_del (&session_set, node);
    session_count--;
}

void
hev_socks5_tunnel_update_session (HevListNode *node)
{
    int max_session_count;

    max_session_count = hev_config_get_misc_max_session_count ();
    if (!max_session_count)
        return;

    if (hev_socks5_tunnel_is_evicted (node))
        return;

    hev_list_del (&session_set, node);
    hev_list_add_tail (&session_set, node);
}

static void
hev_socks5_session_task_entry (void *data)
{
    HevSocks5Session *s = data;

    hev_socks5_session_run (s);

    hev_socks5_tunnel_delete_session (hev_socks5_session_get_node (s));
    hev_object_unref (HEV_OBJECT (s));
}

static void
tcp_pcb_set_gso (struct tcp_pcb *pcb)
{
    /* Leave room for IP and TCP headers within the 64K packet limit. */
    const unsigned int max = 65535 - 128;
    unsigned int n = (max + pcb->mss - 1) / pcb->mss;

    /*
     * Send super segments of n segments, the tunnel device segments them
     * into packets of gso_mss on demand (GSO), or they are delivered as is
     * (jumbo). The segments are shrunk evenly to fill up the 64K limit, so
     * a 64K buffer goes out as one packet instead of a full and a small one.
     */
    pcb->gso_mss = max / n;
    pcb->mss = n * pcb->gso_mss;
}

static err_t
tcp_accept_handler (void *arg, struct tcp_pcb *pcb, err_t err)
{
    HevSocks5SessionTCP *tcp;
    HevListNode *node;
    int stack_size;
    HevTask *task;

    if (err != ERR_OK)
        return err;

    if (!READ_ONCE (run))
        return ERR_RST;

    /*
     * The task goes first: the session can't be destroyed here, its destructor
     * takes the mutex that is held while lwIP calls us.
     */
    stack_size = hev_config_get_misc_task_stack_size ();
    task = hev_task_new (stack_size);
    if (!task)
        return ERR_MEM;

    tcp = hev_socks5_session_tcp_new (pcb, &mutex);
    if (!tcp) {
        hev_task_unref (task);
        return ERR_MEM;
    }

    if (hev_tunnel_get_offload () &
        (HEV_TUNNEL_OFFLOAD_TCP | HEV_TUNNEL_OFFLOAD_JUMBO))
        tcp_pcb_set_gso (pcb);

    hev_socks5_session_set_task (HEV_SOCKS5_SESSION (tcp), task);
    node = hev_socks5_session_get_node (HEV_SOCKS5_SESSION (tcp));
    hev_socks5_tunnel_insert_session (node);
    hev_task_run (task, hev_socks5_session_task_entry, tcp);
    hev_task_wakeup (task_lwip_timer);

    return ERR_OK;
}

static void
dns_recv_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                  const ip_addr_t *addr, u16_t port)
{
    HevMappedDNS *dns = arg;
    struct pbuf *b;
    int res;

    LOG_D ("%p mapped dns handle", dns);

    b = pbuf_alloc (PBUF_TRANSPORT, UDP_BUF_SIZE, PBUF_RAM);
    if (!b)
        goto exit;

    res = hev_mapped_dns_handle (dns, p->payload, p->len, b->payload, b->len);
    if (res < 0)
        goto free;

    b->len = res;
    b->tot_len = res;
    udp_sendfrom (pcb, b, &pcb->local_ip, pcb->local_port);

free:
    pbuf_free (b);
exit:
    pbuf_free (p);
    udp_recv (pcb, NULL, NULL);
    udp_remove (pcb);
}

/*
 * The datagram that created the pcb is delivered to it after we return. The
 * pcb must not be removed before, lwIP would create a new one for it again.
 */
static void
udp_drop_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                  const ip_addr_t *addr, u16_t port)
{
    pbuf_free (p);
    udp_recv (pcb, NULL, NULL);
    udp_remove (pcb);
}

static void
udp_recv_handler (void *arg, struct udp_pcb *pcb, struct pbuf *p,
                  const ip_addr_t *addr, u16_t port)
{
    HevSocks5SessionUDP *udp;
    HevListNode *node;
    HevMappedDNS *dns;
    int stack_size;
    HevTask *task;

    if (!READ_ONCE (run)) {
        udp_recv (pcb, udp_drop_handler, NULL);
        return;
    }

    dns = hev_mapped_dns_get ();
    if (dns && addr->type == IPADDR_TYPE_V4) {
        int faddr = hev_config_get_mapdns_address ();
        int fport = hev_config_get_mapdns_port ();
        if (fport == port && faddr == ip_2_ip4 (addr)->addr) {
            udp_recv (pcb, dns_recv_handler, dns);
            return;
        }
    }

    /* As for TCP, the session can't be destroyed here. */
    stack_size = hev_config_get_misc_task_stack_size ();
    task = hev_task_new (stack_size);
    if (!task) {
        udp_recv (pcb, udp_drop_handler, NULL);
        return;
    }

    udp = hev_socks5_session_udp_new (pcb, &mutex);
    if (!udp) {
        hev_task_unref (task);
        udp_recv (pcb, udp_drop_handler, NULL);
        return;
    }

    hev_socks5_session_set_task (HEV_SOCKS5_SESSION (udp), task);
    node = hev_socks5_session_get_node (HEV_SOCKS5_SESSION (udp));
    hev_socks5_tunnel_insert_session (node);
    hev_task_run (task, hev_socks5_session_task_entry, udp);
    hev_task_wakeup (task_lwip_timer);
}

static void
event_task_entry (void *data)
{
    HevListNode *node;
    int val;

    LOG_D ("socks5 tunnel event task run");

    hev_task_add_fd (task_event, event_fds[0], POLLIN);

    hev_task_io_read (event_fds[0], &val, sizeof (val), NULL, NULL);

    WRITE_ONCE (run, 0);
    atomic_fetch_and (&tsync, ~SYNC_SENT);

    node = hev_list_first (&session_set);
    for (; node; node = hev_list_node_next (node)) {
        HevSocks5SessionData *sd;

        sd = container_of (node, HevSocks5SessionData, node);
        hev_socks5_session_terminate (sd->self);
    }

    hev_task_join (task_lwip_io);
    hev_task_join (task_lwip_timer);
    hev_task_del_fd (task_event, event_fds[0]);
}

/*
 * Packets are read into MTU-sized pbufs. The ones that are queued by a
 * session would pin the whole buffer, so small packets are moved into a
 * pbuf that fits them.
 */
static struct pbuf *
tunnel_compact_pbuf (struct pbuf *buf)
{
    struct pbuf *p;

    if (buf->next || (buf->tot_len > COMPACT_PBUF_SIZE))
        return buf;

    p = pbuf_alloc (PBUF_RAW, buf->tot_len, PBUF_RAM);
    if (!p)
        return buf;

    memcpy (p->payload, buf->payload, buf->tot_len);
    pbuf_free (buf);

    return p;
}

static void
lwip_io_task_entry (void *data)
{
    const unsigned int mtu = hev_config_get_tunnel_mtu ();
    unsigned int i;

    LOG_D ("socks5 tunnel lwip task run");

    hev_tunnel_add_task (tun_fd, task_lwip_io);

    for (i = 0; READ_ONCE (run);) {
        struct pbuf *buf;
        uint8_t *data;

        buf = hev_tunnel_read (tun_fd, mtu, task_io_yielder, NULL);
        if (!buf)
            continue;

        stat_tx_packets++;
        stat_tx_bytes += buf->tot_len;

        buf = tunnel_compact_pbuf (buf);

        data = buf->payload;
        /* Local Unbar addition (1.0.41): count EVERY packet toward
         * TUN_READ_BATCH, not just UDP — during a pure-TCP bulk
         * download no UDP arrives, so the loop would otherwise never
         * yield and starve the TCP sessions. */
        i++;

        /*
         * Local Unbar addition (1.0.13, IPv6 in 1.0.37): answer QUIC
         * (UDP port 443) and non-DNS UDP with ICMP Port Unreachable so
         * the app falls back to TCP at once instead of waiting out a
         * UDP timeout. The packet never enters lwIP. Note the TX
         * counter above already counted it, so rejected packets are
         * included in the TX statistics (and the ICMP reply counts
         * toward RX) — keep that in mind when reading HevStats.
         */
        {
            unsigned int ip_hlen = 0;
            int dst_port;

            dst_port = tunnel_udp_dst_port (data, buf->tot_len, &ip_hlen);
            if (dst_port && ((reject_non_dns_udp && (dst_port != 53)) ||
                             (reject_quic && (dst_port == 443)))) {
                tunnel_send_icmp_port_unreachable (data, buf->tot_len,
                                                   ip_hlen);
                pbuf_free (buf);
                continue;
            }
        }

        hev_task_mutex_lock (&mutex);
        {
            /* Local Unbar addition (1.0.14): LOG ONLY — netif->input()
             * errors with protocol, packet length and error code. The
             * TX counter above is untouched (TUN ingress only, counted
             * before input by design). */
            err_t in_err = netif->input (buf, netif);
            if (in_err != ERR_OK) {
                unsigned int proto = 0;
                const char *protoname = "other";
                data = buf->payload;
                if (buf->tot_len >= 1) {
                    if ((data[0] >> 4) == 4)
                        proto = (buf->tot_len > 9) ? data[9] : 0;
                    else if ((data[0] >> 4) == 6)
                        proto = (buf->tot_len > 6) ? data[6] : 0;
                }
                if (proto == IP_PROTO_TCP)
                    protoname = "TCP";
                else if (proto == IP_PROTO_UDP)
                    protoname = "UDP";
                LOG_W ("socks5 tunnel netif input error proto=%s(%u) "
                       "len=%u err=%d",
                       protoname, proto, buf->tot_len, in_err);
                pbuf_free (buf);
            }
        }
        hev_task_mutex_unlock (&mutex);

        /*
         * Let the UDP sessions forward what has been received so far, instead
         * of reading packets that will be dropped by full session queues.
         */
        if (i >= TUN_READ_BATCH) {
            hev_task_yield (HEV_TASK_YIELD);
            i = 0;
        }
    }

    hev_tunnel_del_task (tun_fd, task_lwip_io);
}

static void
lwip_timer_task_entry (void *data)
{
    unsigned int i;

    LOG_D ("socks5 tunnel timer task run");

    for (i = 1; READ_ONCE (run); i++) {
        hev_task_mutex_lock (&mutex);
        tcp_tmr ();

        if ((i & 3) == 0) {
#if IP_REASSEMBLY
            ip_reass_tmr ();
#endif
#if LWIP_IPV6
            nd6_tmr ();
#if LWIP_IPV6_REASS
            ip6_reass_tmr ();
#endif
#endif
        }
        hev_task_mutex_unlock (&mutex);

        if (hev_list_first (&session_set) || evicted_count)
            hev_task_sleep (TCP_TMR_INTERVAL);
        else {
#if defined(__GLIBC__) && !defined(ENABLE_LIBRARY)
            /* Release free memory from the heap */
            malloc_trim (0);
#endif
            hev_task_yield (HEV_TASK_WAITIO);
        }
    }
}

static int
tunnel_init (int extern_tun_fd)
{
    const char *script_path, *name, *guid, *ipv4, *ipv6;
    int multi_queue, offload, res;
    unsigned int mtu;

    offload = hev_config_get_tunnel_offload ();

    if (extern_tun_fd >= 0) {
#ifndef _WIN32
        int nonblock = 1;

        res = ioctl (extern_tun_fd, FIONBIO, (char *)&nonblock);
        if (res < 0) {
            LOG_E ("socks5 tunnel non-blocking");
            return -1;
        }
#endif

        tun_fd = extern_tun_fd;
        hev_tunnel_set_offload (tun_fd, offload);
        return 0;
    }

    tun_fd_local = 1;
    name = hev_config_get_tunnel_name ();
    multi_queue = hev_config_get_tunnel_multi_queue ();
    guid = hev_config_get_tunnel_guid ();
    tun_fd = hev_tunnel_open (name, multi_queue, guid);
    if (tun_fd < 0) {
        LOG_E ("socks5 tunnel open (%s)", strerror (errno));
        return -1;
    }
    hev_tunnel_set_offload (tun_fd, offload);

    mtu = hev_config_get_tunnel_mtu ();
    res = hev_tunnel_set_mtu (mtu);
    if (res < 0) {
        LOG_E ("socks5 tunnel mtu");
        return -1;
    }

    ipv4 = hev_config_get_tunnel_ipv4_address ();
    if (ipv4) {
        res = hev_tunnel_set_ipv4 (ipv4, 32);
        if (res < 0) {
            LOG_E ("socks5 tunnel ipv4");
            return -1;
        }
    }

    ipv6 = hev_config_get_tunnel_ipv6_address ();
    if (ipv6) {
        res = hev_tunnel_set_ipv6 (ipv6, 128);
        if (res < 0) {
            LOG_E ("socks5 tunnel ipv6");
            return -1;
        }
    }

    res = hev_tunnel_set_state (1);
    if (res < 0) {
        LOG_E ("socks5 tunnel state");
        return -1;
    }

    script_path = hev_config_get_tunnel_post_up_script ();
    if (script_path)
        hev_exec_run (script_path, hev_tunnel_get_name (),
                      hev_tunnel_get_index (), 0);

    return 0;
}

static void
tunnel_fini (void)
{
    const char *script_path;

    if (!tun_fd_local) {
        /* The fd belongs to the caller, only release what we hold for it. */
        hev_tunnel_set_offload (-1, 0);
        tun_fd = -1;
        return;
    }

    script_path = hev_config_get_tunnel_pre_down_script ();
    if (script_path)
        hev_exec_run (script_path, hev_tunnel_get_name (),
                      hev_tunnel_get_index (), 1);

    if (tun_fd >= 0) {
        hev_tunnel_close (tun_fd);
        tun_fd_local = 0;
        tun_fd = -1;
    }
}

static int
gateway_init (void)
{
    static struct netif _netif;
    ip4_addr_t addr4, mask, gw;
    ip6_addr_t addr6;

    netif = netif_add_noaddr (&_netif, NULL, netif_init_handler, ip_input);
    if (!netif) {
        LOG_E ("socks5 tunnel netif");
        return -1;
    }

    ip4_addr_set_loopback (&addr4);
    ip4_addr_set_any (&mask);
    ip4_addr_set_any (&gw);
    netif_set_addr (netif, &addr4, &mask, &gw);

    ip6_addr_set_loopback (&addr6);
    netif_add_ip6_address (netif, &addr6, NULL);

    netif_set_up (netif);
    netif_set_link_up (netif);
    netif_set_default (netif);
    netif_set_flags (netif, NETIF_FLAG_PRETEND_TCP);
    netif_set_flags (netif, NETIF_FLAG_PRETEND_UDP);

    if (hev_tunnel_get_offload () & HEV_TUNNEL_OFFLOAD_CSUM)
        NETIF_SET_CHECKSUM_CTRL (netif, NETIF_CHECKSUM_ENABLE_ALL &
                                            ~(NETIF_CHECKSUM_GEN_TCP |
                                              NETIF_CHECKSUM_GEN_UDP));

    if (hev_config_get_tunnel_icmp ())
        netif_set_flags (netif, NETIF_FLAG_PRETEND_ICMP);

    tcp = tcp_new_ip_type (IPADDR_TYPE_ANY);
    if (!tcp) {
        LOG_E ("socks5 tunnel tcp");
        return -1;
    }

    tcp_bind_netif (tcp, netif);
    tcp_bind (tcp, NULL, 0);
    tcp = tcp_listen (tcp);
    tcp_accept (tcp, tcp_accept_handler);

    udp = udp_new_ip_type (IPADDR_TYPE_ANY);
    if (!udp) {
        LOG_E ("socks5 tunnel udp");
        return -1;
    }

    udp_bind_netif (udp, netif);
    udp_bind (udp, NULL, 0);
    udp_recv (udp, udp_recv_handler, NULL);

    return 0;
}

static void
gateway_fini (void)
{
    if (udp) {
        udp_remove (udp);
        udp = NULL;
    }
    if (tcp) {
        tcp_close (tcp);
        tcp = NULL;
    }
    if (netif) {
        netif_remove (netif);
        netif = NULL;
    }
}

static int
event_task_init (void)
{
    int res;

    res = hev_task_io_socket_socketpair (PF_LOCAL, SOCK_STREAM, 0, event_fds);
    if (res < 0) {
        LOG_E ("socks5 tunnel event");
        return -1;
    }

    if ((hev_task_io_fcntl (event_fds[0], F_SETFD, FD_CLOEXEC) < 0) ||
        (hev_task_io_fcntl (event_fds[1], F_SETFD, FD_CLOEXEC) < 0)) {
        LOG_E ("socks5 tunnel event cloexec");
        return -1;
    }

    task_event = hev_task_new (-1);
    if (!task_event) {
        LOG_E ("socks5 tunnel task event");
        return -1;
    }

    return 0;
}

static void
event_task_fini (void)
{
    if (task_event) {
        hev_task_unref (task_event);
        task_event = NULL;
    }

    if (event_fds[0] >= 0) {
        hev_task_io_close (event_fds[0]);
        event_fds[0] = -1;
    }
    if (event_fds[1] >= 0) {
        hev_task_io_close (event_fds[1]);
        event_fds[1] = -1;
    }
}

static int
lwip_io_task_init (void)
{
    task_lwip_io = hev_task_new (-1);
    if (!task_lwip_io) {
        LOG_E ("socks5 tunnel task lwip");
        return -1;
    }
    hev_task_set_priority (task_lwip_io, 1);

    return 0;
}

static void
lwip_io_task_fini (void)
{
    if (task_lwip_io) {
        hev_task_unref (task_lwip_io);
        task_lwip_io = NULL;
    }
}

static int
lwip_timer_task_init (void)
{
    task_lwip_timer = hev_task_new (-1);
    if (!task_lwip_timer) {
        LOG_E ("socks5 tunnel task timer");
        return -1;
    }
    hev_task_set_priority (task_lwip_timer, 1);

    return 0;
}

static void
lwip_timer_task_fini (void)
{
    if (task_lwip_timer) {
        hev_task_unref (task_lwip_timer);
        task_lwip_timer = NULL;
    }
}

static int
mapped_dns_init (void)
{
    HevMappedDNS *dns;
    int cache_size;
    int network;
    int netmask;

    network = hev_config_get_mapdns_network ();
    netmask = hev_config_get_mapdns_netmask ();
    cache_size = hev_config_get_mapdns_cache_size ();

    if (!cache_size)
        return 0;

    dns = hev_mapped_dns_new (network, netmask, cache_size);
    if (!dns)
        return -1;

    hev_mapped_dns_put (dns);

    return 0;
}

static void
mapped_dns_fini (void)
{
    HevMappedDNS *dns;

    dns = hev_mapped_dns_get ();
    if (dns) {
        hev_object_unref (HEV_OBJECT (dns));
        hev_mapped_dns_put (NULL);
    }
}

int
hev_socks5_tunnel_init (int tun_fd)
{
    int res;

    LOG_D ("socks5 tunnel init");

    res = tunnel_init (tun_fd);
    if (res < 0)
        goto exit;

    res = gateway_init ();
    if (res < 0)
        goto exit;

    res = event_task_init ();
    if (res < 0)
        goto exit;

    res = lwip_io_task_init ();
    if (res < 0)
        goto exit;

    res = lwip_timer_task_init ();
    if (res < 0)
        goto exit;

    res = mapped_dns_init ();
    if (res < 0)
        goto exit;

#ifdef SIGPIPE
    signal (SIGPIPE, SIG_IGN);
#endif

    hev_task_mutex_init (&mutex);

    atomic_fetch_or (&tsync, SYNC_SEND);

    return 0;

exit:
    hev_socks5_tunnel_fini ();
    return -1;
}

void
hev_socks5_tunnel_fini (void)
{
    LOG_D ("socks5 tunnel fini");

retry:
    if (atomic_fetch_and (&tsync, ~(SYNC_SEND | SYNC_SENT)) & SYNC_WAIT) {
        usleep (500);
        goto retry;
    }

    mapped_dns_fini ();
    lwip_timer_task_fini ();
    lwip_io_task_fini ();
    event_task_fini ();
    gateway_fini ();
    tunnel_fini ();

    stat_tx_packets = 0;
    stat_rx_packets = 0;
    stat_tx_bytes = 0;
    stat_rx_bytes = 0;

    /* Local Unbar addition (1.0.13): reset the ICMP reject flags. */
    reject_quic = 1;
    reject_non_dns_udp = 1;
}

int
hev_socks5_tunnel_run (void)
{
    LOG_D ("socks5 tunnel run");

    if (atomic_fetch_and (&tsync, ~SYNC_STOP) & SYNC_STOP)
        return 0;

    task_event = hev_task_ref (task_event);
    hev_task_run (task_event, event_task_entry, NULL);

    task_lwip_io = hev_task_ref (task_lwip_io);
    hev_task_run (task_lwip_io, lwip_io_task_entry, NULL);

    task_lwip_timer = hev_task_ref (task_lwip_timer);
    hev_task_run (task_lwip_timer, lwip_timer_task_entry, NULL);

    WRITE_ONCE (run, 1);
    hev_task_system_run ();

    return 0;
}

void
hev_socks5_tunnel_stop (void)
{
    int res;

    LOG_D ("socks5 tunnel stop");

retry:
    res = atomic_fetch_or (&tsync, SYNC_WAIT);
    if (res & SYNC_WAIT) {
        usleep (500);
        goto retry;
    }

    if (res & SYNC_SEND) {
        res = atomic_fetch_or (&tsync, SYNC_SENT);
        if (!(res & SYNC_SENT)) {
            res = hev_task_io_write (event_fds[1], &res, 1, hev_task_io_nowait,
                                     NULL);
            assert (res > 0 && "socks5 tunnel write event");
        }
    } else {
        atomic_fetch_or (&tsync, SYNC_STOP);
    }

    atomic_fetch_and (&tsync, ~SYNC_WAIT);
}

EXPORT_SYMBOL void
hev_socks5_tunnel_stats (size_t *tx_packets, size_t *tx_bytes,
                         size_t *rx_packets, size_t *rx_bytes)
{
    LOG_D ("socks5 tunnel stats");

    if (tx_packets)
        *tx_packets = stat_tx_packets;

    if (tx_bytes)
        *tx_bytes = stat_tx_bytes;

    if (rx_packets)
        *rx_packets = stat_rx_packets;

    if (rx_bytes)
        *rx_bytes = stat_rx_bytes;
}

/*
 * Local Unbar addition (1.0.13): enable or disable answering QUIC
 * (UDP port 443) with ICMP Port Unreachable.
 */
EXPORT_SYMBOL void
hev_socks5_tunnel_set_reject_quic (int enabled)
{
    reject_quic = enabled ? 1 : 0;
}

/*
 * Local Unbar addition (1.0.13): enable or disable answering non-DNS
 * UDP (any UDP destination port other than 53) with ICMP Port
 * Unreachable.
 */
EXPORT_SYMBOL void
hev_socks5_tunnel_set_reject_non_dns_udp (int enabled)
{
    reject_non_dns_udp = enabled ? 1 : 0;
}
