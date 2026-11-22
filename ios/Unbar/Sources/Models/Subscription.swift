// Subscription.swift — a subscription: a URL that serves a list of
// servers, refreshed into the server list. A subscription's servers
// carry its id (see Server.subscriptionId); deleting the subscription
// deletes its servers, and a refresh replaces only that
// subscription's servers — manual servers are never touched.

import Foundation

public struct Subscription: Identifiable, Codable, Equatable {
    public var id: UUID
    public var url: String
    public var lastUpdated: Date?
    public var serverCount: Int

    public init(id: UUID = UUID(), url: String,
                lastUpdated: Date? = nil, serverCount: Int = 0) {
        self.id = id
        self.url = url
        self.lastUpdated = lastUpdated
        self.serverCount = serverCount
    }
}
