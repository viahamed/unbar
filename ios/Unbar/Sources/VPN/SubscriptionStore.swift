// SubscriptionStore.swift — owns the subscriptions and their
// servers.
//
// A subscription is a URL that serves a list of servers. Refreshing
// it fetches the feed, validates every candidate, and REPLACES only
// that subscription's servers (subscriptionId match) — manual
// servers are never touched. Deleting a subscription deletes its
// servers too. Multiple subscriptions are supported; the same URL
// is one subscription, updated in place.
//
// If the active server vanishes in a refresh, it is remapped to the
// new row when the same zone+key survives the refresh, otherwise the
// active server is cleared. Auto-update on app open runs in the
// background when the switch is on.

import Foundation
import Combine

public final class SubscriptionStore: ObservableObject {
    public static let shared = SubscriptionStore()

    @Published public private(set) var subscriptions: [Subscription] = []
    @Published public private(set) var updatingIds: Set<UUID> = []
    @Published public private(set) var status: SubscriptionStatus?

    private let workQueue = DispatchQueue(label: "org.breakthebarriers.unbar.subscriptions",
                                          qos: .utility)

    private init() {
        load()
    }

    // MARK: - Persistence

    public func load() {
        guard let defaults = AppGroup.defaults,
              let data = defaults.data(forKey: SharedKey.subscriptions),
              let decoded = try? JSONDecoder().decode([Subscription].self, from: data) else {
            return
        }
        subscriptions = decoded
    }

    public func save() {
        guard let defaults = AppGroup.defaults,
              let data = try? JSONEncoder().encode(subscriptions) else {
            return
        }
        defaults.set(data, forKey: SharedKey.subscriptions)
    }

    public func subscription(id: UUID) -> Subscription? {
        return subscriptions.first(where: { $0.id == id })
    }

    // MARK: - Add / refresh / delete

    /// Adds the subscription (or finds the existing one for the same
    /// URL) and refreshes it. Completion reports the refresh result.
    public func addOrUpdateSubscription(url: String,
                                        completion: ((SubscriptionStatus) -> Void)? = nil) {
        let cleanUrl = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let urlError = SubscriptionFetcher.validateHttpsUrl(cleanUrl) {
            let errorStatus = SubscriptionStatus.error(kind: urlError, detail: nil)
            status = errorStatus
            SharedLog.append("Subscription rejected (\(urlError)): \(cleanUrl)")
            completion?(errorStatus)
            return
        }
        let target: Subscription
        if let existing = subscriptions.first(where: { $0.url == cleanUrl }) {
            target = existing
        } else {
            target = Subscription(url: cleanUrl)
            subscriptions.append(target)
            save()
        }
        refreshSubscriptionInternal(target, completion: completion)
    }

    /// Edits a subscription's URL in place: the same subscription
    /// (same id) is updated, so its servers stay linked until the
    /// refresh replaces them by subscriptionId. A URL already owned
    /// by another subscription is rejected and nothing is saved.
    public func updateSubscriptionUrl(id: UUID, url: String,
                                      completion: ((SubscriptionStatus) -> Void)? = nil) {
        let cleanUrl = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let urlError = SubscriptionFetcher.validateHttpsUrl(cleanUrl) {
            let errorStatus = SubscriptionStatus.error(kind: urlError, detail: nil)
            status = errorStatus
            SharedLog.append("Subscription URL edit rejected (\(urlError)): \(cleanUrl)")
            completion?(errorStatus)
            return
        }
        guard let index = subscriptions.firstIndex(where: { $0.id == id }) else {
            completion?(.neverUpdated)
            return
        }
        if subscriptions.contains(where: { $0.url == cleanUrl && $0.id != id }) {
            let errorStatus = SubscriptionStatus.error(kind: .invalidURL, detail: nil)
            status = errorStatus
            SharedLog.append("Subscription URL edit rejected (URL already used): \(cleanUrl)")
            completion?(errorStatus)
            return
        }
        subscriptions[index].url = cleanUrl
        save()
        refreshSubscriptionInternal(subscriptions[index], completion: completion)
    }

    public func refreshSubscription(id: UUID,
                                    completion: ((SubscriptionStatus) -> Void)? = nil) {
        guard let target = subscription(id: id) else {
            completion?(.neverUpdated)
            return
        }
        refreshSubscriptionInternal(target, completion: completion)
    }

    public func deleteSubscription(id: UUID) {
        guard let target = subscription(id: id) else { return }
        let settings = AppSettings.shared
        let activeBelongsToSub = settings.activeServer?.subscriptionId == id
        _ = settings.replaceSubscriptionServers(subscriptionId: id, with: [])
        if activeBelongsToSub {
            settings.activeServerId = nil
            settings.save()
        }
        subscriptions.removeAll(where: { $0.id == id })
        save()
        let statusIsError: Bool
        if case .error = status { statusIsError = true } else { statusIsError = false }
        if subscriptions.isEmpty && !statusIsError {
            status = nil
        }
        SharedLog.append("Deleted subscription \(target.url)")
    }

    /// Refreshes every subscription in the background, when the
    /// auto-update switch is on. Called on app open.
    public func refreshAllSubscriptionsIfNeeded() {
        guard AppSettings.shared.autoUpdateSubscriptions else { return }
        let all = subscriptions
        guard !all.isEmpty else { return }
        workQueue.async { [weak self] in
            guard let self = self else { return }
            var lastError: SubscriptionStatus?
            for sub in all {
                let semaphore = DispatchSemaphore(value: 0)
                var result: SubscriptionStatus?
                DispatchQueue.main.async {
                    self.refreshSubscriptionInternal(sub) { status in
                        result = status
                        semaphore.signal()
                    }
                }
                semaphore.wait()
                if let result = result, case .error = result {
                    lastError = result
                }
            }
            if let lastError = lastError {
                DispatchQueue.main.async { self.status = lastError }
            }
        }
    }

    // MARK: - Refresh internals

    private func refreshSubscriptionInternal(_ subscription: Subscription,
                                             completion: ((SubscriptionStatus) -> Void)? = nil) {
        if updatingIds.contains(subscription.id) {
            completion?(status ?? .updating)
            return
        }
        updatingIds.insert(subscription.id)
        status = .updating
        SharedLog.append("Updating subscription \(subscription.url)")

        SubscriptionFetcher.fetch(url: subscription.url) { [weak self] result in
            guard let self = self else { return }
            self.updatingIds.remove(subscription.id)
            switch result {
            case .failure(let kind, let detail):
                let errorStatus = SubscriptionStatus.error(kind: kind, detail: detail)
                self.status = errorStatus
                SharedLog.append("Subscription failed \(subscription.url): \(kind) \(detail ?? "")")
                completion?(errorStatus)
            case .success(let output):
                completion?(self.applyFetchOutput(output, to: subscription))
            }
        }
    }

    /// Validates the fetched candidates and replaces the
    /// subscription's servers with the accepted ones. Returns the
    /// status for the UI ("Updated · N servers" / "Updated · N
    /// servers, M skipped").
    private func applyFetchOutput(_ output: SubscriptionFetchOutput,
                                  to subscription: Subscription) -> SubscriptionStatus {
        // The subscription may have been deleted while fetching.
        guard var currentSub = self.subscription(id: subscription.id) else {
            let fallback = SubscriptionStatus.neverUpdated
            status = fallback
            return fallback
        }
        let settings = AppSettings.shared
        let existingOtherServers = settings.servers.filter { $0.subscriptionId != subscription.id }
        var accepted: [Server] = []
        var skipped = output.skippedDuringParse
        for candidate in output.candidates {
            let validationError = ServerValidation.validateEntry(
                servers: existingOtherServers + accepted,
                excludeId: nil,
                zone: candidate.zone,
                publicKeyB64: candidate.publicKeyB64)
            if validationError != nil {
                skipped += 1
            } else {
                accepted.append(Server(name: candidate.name, zone: candidate.zone,
                                       serverKeyB64: candidate.publicKeyB64,
                                       subscriptionId: subscription.id))
            }
        }

        let activeServer = settings.activeServer
        let inserted = settings.replaceSubscriptionServers(subscriptionId: subscription.id,
                                                           with: accepted)
        currentSub.lastUpdated = Date()
        currentSub.serverCount = accepted.count
        if let index = subscriptions.firstIndex(where: { $0.id == subscription.id }) {
            subscriptions[index] = currentSub
        }
        save()

        // The active server belonged to this subscription: remap it
        // to the new row when the same zone+key survived the refresh,
        // otherwise clear it.
        if let active = activeServer, active.subscriptionId == subscription.id {
            if let match = inserted.first(where: {
                $0.zone.trimmingCharacters(in: .whitespacesAndNewlines) ==
                    active.zone.trimmingCharacters(in: .whitespacesAndNewlines)
                    && $0.serverKeyB64.trimmingCharacters(in: .whitespacesAndNewlines) ==
                    active.serverKeyB64.trimmingCharacters(in: .whitespacesAndNewlines)
            }) {
                settings.activeServerId = match.id
            } else {
                settings.activeServerId = nil
            }
            settings.save()
        }

        let updatedStatus = SubscriptionStatus.updated(serverCount: accepted.count,
                                                       skippedCount: skipped)
        status = updatedStatus
        SharedLog.append("Updated subscription \(subscription.url): \(accepted.count) servers, \(skipped) skipped")
        return updatedStatus
    }
}
