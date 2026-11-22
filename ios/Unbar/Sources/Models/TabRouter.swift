// TabRouter.swift — which tab the app shows.
//
// Home's "Change server" button switches to the Servers tab instead of
// pushing a second Servers screen inside Home's navigation stack.

import Foundation
import Combine

public final class TabRouter: ObservableObject {
    public static let shared = TabRouter()

    @Published public var selectedTab: Int = 0

    private init() {}
}
