// AppUpdateChecker.swift — checks GitHub Releases for a newer build.
//
// Mirrors the Android AppUpdateChecker: one unauthenticated GET to
// api.github.com/repos/viahamed/unbar/releases/latest, semver
// compare (strip v/prefixes/suffixes, release > pre-release). One
// attempt, no retries: a failure is reported, not repeated. The check
// only runs when the user taps "Check for update" — never on its own,
// never in a loop.
//
// iOS difference: the app cannot install from inside itself, so the
// checker returns the release page URL (html_url) instead of an APK
// download — the dialog opens that page in Safari. There is no
// download state and no install-permission flow on iOS.
//
// Fetch security follows SubscriptionFetcher: HTTPS only, redirects
// followed by hand with the scheme re-verified on every hop, body
// capped at 512 KB. Completion runs on the main queue.

import Foundation

public struct ReleaseInfo {
    public let tag: String
    /// Tag without the leading "v" — the version shown to the user.
    public let version: String
    public let notes: String
    /// The release page on GitHub, opened in Safari.
    public let pageURL: String

    public init(tag: String, version: String, notes: String, pageURL: String) {
        self.tag = tag
        self.version = version
        self.notes = notes
        self.pageURL = pageURL
    }
}

public enum UpdateFailureKind {
    case network
    case badResponse
    case noPage
}

public enum UpdateCheckResult {
    case upToDate
    case available(ReleaseInfo)
    case failed(UpdateFailureKind)
}

public enum AppUpdateChecker {
    private static let latestReleaseURL =
        "https://api.github.com/repos/viahamed/unbar/releases/latest"
    private static let readTimeout: TimeInterval = 30
    private static let maxRedirects = 5
    private static let maxBodyBytes = 512 * 1024
    private static let maxNotesChars = 5000

    /// Fetches the latest release and compares it with
    /// `currentVersion` (the installed version name, e.g. "1.0.0").
    /// One attempt, no retries.
    public static func checkForUpdate(currentVersion: String,
                                      completion: @escaping (UpdateCheckResult) -> Void) {
        guard let startUrl = URL(string: latestReleaseURL) else {
            completion(.failed(.badResponse))
            return
        }
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = readTimeout
        configuration.timeoutIntervalForResource = 60
        let task = FetchTask(startUrl: startUrl, currentVersion: currentVersion,
                             completion: completion)
        task.start(with: configuration)
    }

    /// Compares dotted version numbers ("1.0.0" vs "v1.0.1"). A
    /// leading "v" and build metadata ("+…") are ignored; a
    /// pre-release suffix ("-debug", "-beta") ranks below the same
    /// plain version, and missing parts count as zero ("1.0" ==
    /// "1.0.0"). Non-numeric segments count as zero instead of
    /// crashing the compare. Returns a negative number when `a` is
    /// older than `b`, zero when equal, a positive number when `a`
    /// is newer.
    public static func compareVersions(_ a: String, _ b: String) -> Int {
        let (coreA, preA) = splitVersion(a)
        let (coreB, preB) = splitVersion(b)
        let partsA = coreA.split(separator: ".")
        let partsB = coreB.split(separator: ".")
        let count = max(partsA.count, partsB.count)
        for i in 0..<count {
            let x = i < partsA.count ? leadingDigits(String(partsA[i])) : 0
            let y = i < partsB.count ? leadingDigits(String(partsB[i])) : 0
            if x != y { return x < y ? -1 : 1 }
        }
        // Same numbers: a plain release outranks a pre-release.
        switch (preA, preB) {
        case (nil, nil): return 0
        case (nil, _): return 1
        case (_, nil): return -1
        case let (a?, b?): return a < b ? -1 : (a > b ? 1 : 0)
        }
    }

    private static func splitVersion(_ raw: String) -> (String, String?) {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") { s.removeFirst() }
        if let plus = s.firstIndex(of: "+") { s = String(s[..<plus]) }
        if let dash = s.firstIndex(of: "-") {
            return (String(s[..<dash]), String(s[s.index(after: dash)...]))
        }
        return (s, nil)
    }

    private static func leadingDigits(_ segment: String) -> Int {
        let digits = segment.prefix(while: { $0.isNumber })
        return Int(digits) ?? 0
    }

    /// Parses a GitHub latest-release response. A release with a tag
    /// but no page is `noPage`, not a bad response: the release
    /// exists, it just has nothing to open.
    public static func parseRelease(_ body: String) -> ReleaseParse {
        guard let data = body.data(using: .utf8) else { return .badResponse }
        let decoder = JSONDecoder()
        guard let json = try? decoder.decode(GithubReleaseJSON.self, from: data) else {
            return .badResponse
        }
        guard let tag = json.tagName?.trimmingCharacters(in: .whitespacesAndNewlines),
              !tag.isEmpty else { return .badResponse }
        var version = tag
        if version.hasPrefix("v") || version.hasPrefix("V") { version.removeFirst() }
        guard !version.isEmpty else { return .badResponse }
        guard let pageURL = json.htmlUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !pageURL.isEmpty else { return .noPage }
        let notes = String((json.body ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(maxNotesChars))
        return .release(ReleaseInfo(tag: tag, version: version, notes: notes, pageURL: pageURL))
    }

    public enum ReleaseParse {
        case release(ReleaseInfo)
        case noPage
        case badResponse
    }

    /// One release object inside a GitHub latest-release response.
    /// Only tag_name + body + html_url are read; unknown fields are
    /// ignored. No `assets` are read: iOS opens the release page
    /// instead of downloading a file.
    private struct GithubReleaseJSON: Decodable {
        var tagName: String?
        var body: String?
        var htmlUrl: String?

        enum CodingKeys: String, CodingKey {
            case tagName = "tag_name"
            case body
            case htmlUrl = "html_url"
        }
    }

    // MARK: - HTTPS fetch with manual redirect following

    private final class FetchTask: NSObject, URLSessionDataDelegate {
        private let startUrl: URL
        private let currentVersion: String
        private let completion: (UpdateCheckResult) -> Void
        private var session: URLSession?
        private var redirectCount = 0
        private var received = Data()
        private var failure: UpdateCheckResult?
        private var finished = false

        init(startUrl: URL, currentVersion: String,
             completion: @escaping (UpdateCheckResult) -> Void) {
            self.startUrl = startUrl
            self.currentVersion = currentVersion
            self.completion = completion
        }

        func start(with configuration: URLSessionConfiguration) {
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
            self.session = session
            var request = URLRequest(url: startUrl)
            request.httpMethod = "GET"
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            request.setValue("Unbar-iOS", forHTTPHeaderField: "User-Agent")
            session.dataTask(with: request).resume()
        }

        private func finish(with result: UpdateCheckResult) {
            guard !finished else { return }
            finished = true
            session?.invalidateAndCancel()
            session = nil
            DispatchQueue.main.async { self.completion(result) }
        }

        // Redirects are followed manually: at most 5 hops, HTTPS
        // re-verified on every hop, an http downgrade rejected.
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest,
                        completionHandler: @escaping (URLRequest?) -> Void) {
            guard let url = request.url else {
                failure = .failed(.badResponse)
                completionHandler(nil)
                return
            }
            if redirectCount >= AppUpdateChecker.maxRedirects {
                failure = .failed(.network)
                completionHandler(nil)
                return
            }
            if url.scheme?.lowercased() != "https" {
                failure = .failed(.network)
                completionHandler(nil)
                return
            }
            redirectCount += 1
            completionHandler(request)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive data: Data) {
            received.append(data)
            if received.count > AppUpdateChecker.maxBodyBytes {
                failure = .failed(.badResponse)
                dataTask.cancel()
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        didCompleteWithError error: Error?) {
            if let failure = failure {
                finish(with: failure)
                return
            }
            if error != nil {
                finish(with: .failed(.network))
                return
            }
            guard let httpResponse = task.response as? HTTPURLResponse else {
                finish(with: .failed(.network))
                return
            }
            guard (200...299).contains(httpResponse.statusCode) else {
                finish(with: .failed(.network))
                return
            }
            guard let text = String(data: received, encoding: .utf8) else {
                finish(with: .failed(.badResponse))
                return
            }
            switch AppUpdateChecker.parseRelease(text) {
            case .release(let release):
                if AppUpdateChecker.compareVersions(release.version, currentVersion) > 0 {
                    finish(with: .available(release))
                } else {
                    finish(with: .upToDate)
                }
            case .noPage:
                finish(with: .failed(.noPage))
            case .badResponse:
                finish(with: .failed(.badResponse))
            }
        }
    }
}
