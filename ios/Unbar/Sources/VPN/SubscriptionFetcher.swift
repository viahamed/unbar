// SubscriptionFetcher.swift — fetches and parses HTTPS subscription
// feeds into validated-candidate lists.
//
// Security invariants:
// - Only HTTPS URLs (including redirects) are accepted; plain http://
//   is rejected immediately, and an http downgrade on any redirect
//   hop is rejected too.
// - Never parses or imports local SOCKS proxy credentials.
// - The body is capped at 2 MB; a larger body is rejected.
//
// A feed is either a JSON array (or a single JSON object) of server
// objects with zone + public key + name, or a plain-text list of
// unbar:// links, one per line (# comments ignored, HTML rejected).
// Every candidate is re-validated by the store before it becomes a
// server (see ServerValidation).

import Foundation

public enum SubscriptionErrorKind: String, Codable, Equatable {
    case emptyURL
    case httpScheme
    case nonHttpsScheme
    case invalidURL
    case httpStatus
    case invalidFormat
    case network
}

public enum SubscriptionStatus: Equatable {
    case neverUpdated
    case updating
    case updated(serverCount: Int, skippedCount: Int)
    case error(kind: SubscriptionErrorKind, detail: String?)
}

public struct FetchedServerCandidate: Equatable {
    public var name: String
    public var zone: String
    public var publicKeyB64: String

    public init(name: String, zone: String, publicKeyB64: String) {
        self.name = name
        self.zone = zone
        self.publicKeyB64 = publicKeyB64
    }
}

public struct SubscriptionFetchOutput: Equatable {
    public var candidates: [FetchedServerCandidate]
    public var skippedDuringParse: Int

    public init(candidates: [FetchedServerCandidate], skippedDuringParse: Int) {
        self.candidates = candidates
        self.skippedDuringParse = skippedDuringParse
    }
}

public enum SubscriptionFetchResult: Equatable {
    case success(SubscriptionFetchOutput)
    case failure(kind: SubscriptionErrorKind, detail: String?)
}

/// One server object inside a JSON subscription feed. Only zone +
/// public key + name are read; the public key is "pubkey" (the feed
/// format) with "key" accepted as well. Unknown fields are ignored.
private struct SubscriptionServerJSON: Decodable {
    var name: String
    var zone: String
    var publicKeyB64: String

    enum CodingKeys: String, CodingKey {
        case name, zone, pubkey, key
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        zone = try c.decode(String.self, forKey: .zone)
        if let pubkey = try c.decodeIfPresent(String.self, forKey: .pubkey) {
            publicKeyB64 = pubkey
        } else {
            publicKeyB64 = try c.decode(String.self, forKey: .key)
        }
    }

    func toCandidate() -> FetchedServerCandidate {
        let cleanZone = zone.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return FetchedServerCandidate(
            name: cleanName.isEmpty ? cleanZone : cleanName,
            zone: cleanZone,
            publicKeyB64: publicKeyB64.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

public enum SubscriptionFetcher {
    private static let readTimeout: TimeInterval = 30
    private static let maxRedirects = 5
    private static let maxBodyBytes = 2 * 1024 * 1024

    /// Returns the URL problem, or nil when the URL is a usable HTTPS URL.
    public static func validateHttpsUrl(_ rawUrl: String) -> SubscriptionErrorKind? {
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .emptyURL }
        if trimmed.lowercased().hasPrefix("http://") { return .httpScheme }
        if !trimmed.lowercased().hasPrefix("https://") { return .nonHttpsScheme }
        guard let url = URL(string: trimmed) else { return .invalidURL }
        guard url.scheme?.lowercased() == "https" else { return .nonHttpsScheme }
        guard let host = url.host, !host.isEmpty else { return .invalidURL }
        return nil
    }

    /// Fetches the feed at `rawUrl` (HTTPS only). Completion runs on
    /// the main queue.
    public static func fetch(url rawUrl: String,
                             completion: @escaping (SubscriptionFetchResult) -> Void) {
        if let urlError = validateHttpsUrl(rawUrl) {
            completion(.failure(kind: urlError, detail: nil))
            return
        }
        guard let startUrl = URL(string: rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            completion(.failure(kind: .invalidURL, detail: nil))
            return
        }
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = readTimeout
        configuration.timeoutIntervalForResource = 60
        // Note: URLSession has no separate connect timeout (unlike
        // the 15s connect / 30s read split elsewhere); the request
        // timeout above covers an unresponsive server.
        let task = FetchTask(startUrl: startUrl, completion: completion)
        task.start(with: configuration)
    }

    /// Parses a fetched body into candidates. JSON array / single
    /// object, or a plain-text list of unbar:// links.
    public static func parseBody(_ rawBody: String) -> SubscriptionFetchResult {
        var body = rawBody.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.hasPrefix("\u{FEFF}") { body.removeFirst() }
        body = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.isEmpty {
            return .failure(kind: .invalidFormat, detail: nil)
        }
        if body.hasPrefix("[") || body.hasPrefix("{") {
            return parseJsonSubscription(body)
        }
        return parseLinkListSubscription(body)
    }

    private static func parseJsonSubscription(_ body: String) -> SubscriptionFetchResult {
        guard let data = body.data(using: .utf8) else {
            return .failure(kind: .invalidFormat, detail: nil)
        }
        let decoder = JSONDecoder()
        var candidates: [FetchedServerCandidate] = []
        var skipped = 0
        if body.hasPrefix("[") {
            guard let items = try? decoder.decode([FailableServer].self, from: data) else {
                return .failure(kind: .invalidFormat, detail: nil)
            }
            for item in items {
                if let server = item.server {
                    candidates.append(server.toCandidate())
                } else {
                    skipped += 1
                }
            }
        } else {
            guard let item = try? decoder.decode(FailableServer.self, from: data) else {
                return .failure(kind: .invalidFormat, detail: nil)
            }
            if let server = item.server {
                candidates.append(server.toCandidate())
            } else {
                skipped += 1
            }
        }
        return .success(SubscriptionFetchOutput(candidates: candidates, skippedDuringParse: skipped))
    }

    /// Decodes one element, swallowing the failure so a single bad
    /// entry is skipped instead of rejecting the whole feed.
    private struct FailableServer: Decodable {
        var server: SubscriptionServerJSON?
        init(from decoder: Decoder) throws {
            server = try? SubscriptionServerJSON(from: decoder)
        }
    }

    private static func parseLinkListSubscription(_ body: String) -> SubscriptionFetchResult {
        let lines = body
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
        if lines.isEmpty {
            return .failure(kind: .invalidFormat, detail: nil)
        }
        if body.hasPrefix("<") && !lines.contains(where: { $0.lowercased().hasPrefix("unbar://") }) {
            return .failure(kind: .invalidFormat, detail: nil)
        }
        var candidates: [FetchedServerCandidate] = []
        var skipped = 0
        var sawUnbarScheme = false
        for line in lines {
            guard line.lowercased().hasPrefix("unbar://") else {
                skipped += 1
                continue
            }
            sawUnbarScheme = true
            if let candidate = importCandidate(from: line) {
                candidates.append(candidate)
            } else {
                skipped += 1
            }
        }
        if !sawUnbarScheme {
            return .failure(kind: .invalidFormat, detail: nil)
        }
        return .success(SubscriptionFetchOutput(candidates: candidates, skippedDuringParse: skipped))
    }

    /// Parses one unbar:// line into a candidate: first the app's own
    /// link format (unbar://add?c=... or a raw code, via
    /// ConfigCodec), then the bare form (unbar://<base64url-json>)
    /// used by subscription files.
    private static func importCandidate(from line: String) -> FetchedServerCandidate? {
        if let server = ConfigCodec.importServer(from: line) {
            return FetchedServerCandidate(name: server.name, zone: server.zone,
                                          publicKeyB64: server.serverKeyB64)
        }
        let afterScheme = line.dropFirst("unbar://".count)
        let code = afterScheme.split(separator: "?", maxSplits: 1).first.map(String.init) ?? ""
        guard !code.isEmpty,
              let data = Data(base64URL: code),
              let text = String(data: data, encoding: .utf8),
              text.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{"),
              let jsonData = text.data(using: .utf8),
              let server = try? JSONDecoder().decode(SubscriptionServerJSON.self, from: jsonData) else {
            return nil
        }
        return server.toCandidate()
    }

    // MARK: - HTTPS fetch with manual redirect following

    private final class FetchTask: NSObject, URLSessionDataDelegate {
        private let startUrl: URL
        private let completion: (SubscriptionFetchResult) -> Void
        private var session: URLSession?
        private var redirectCount = 0
        private var received = Data()
        private var failure: SubscriptionFetchResult?
        private var finished = false

        init(startUrl: URL, completion: @escaping (SubscriptionFetchResult) -> Void) {
            self.startUrl = startUrl
            self.completion = completion
        }

        func start(with configuration: URLSessionConfiguration) {
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
            self.session = session
            var request = URLRequest(url: startUrl)
            request.httpMethod = "GET"
            request.setValue("application/json, text/plain;q=0.9, */*;q=0.8", forHTTPHeaderField: "Accept")
            request.setValue("Unbar-iOS", forHTTPHeaderField: "User-Agent")
            session.dataTask(with: request).resume()
        }

        private func finish(with result: SubscriptionFetchResult) {
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
                failure = .failure(kind: .invalidURL, detail: nil)
                completionHandler(nil)
                return
            }
            if redirectCount >= SubscriptionFetcher.maxRedirects {
                failure = .failure(kind: .network, detail: "Too many redirects")
                completionHandler(nil)
                return
            }
            if url.scheme?.lowercased() == "http" {
                failure = .failure(kind: .httpScheme, detail: nil)
                completionHandler(nil)
                return
            }
            if url.scheme?.lowercased() != "https" {
                failure = .failure(kind: .nonHttpsScheme, detail: nil)
                completionHandler(nil)
                return
            }
            redirectCount += 1
            completionHandler(request)
        }

        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive data: Data) {
            received.append(data)
            if received.count > SubscriptionFetcher.maxBodyBytes {
                failure = .failure(kind: .invalidFormat, detail: nil)
                dataTask.cancel()
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask,
                        didCompleteWithError error: Error?) {
            if let failure = failure {
                finish(with: failure)
                return
            }
            if let error = error {
                finish(with: .failure(kind: .network, detail: error.localizedDescription))
                return
            }
            guard let httpResponse = task.response as? HTTPURLResponse else {
                finish(with: .failure(kind: .network, detail: nil))
                return
            }
            guard (200...299).contains(httpResponse.statusCode) else {
                finish(with: .failure(kind: .httpStatus, detail: "\(httpResponse.statusCode)"))
                return
            }
            guard let text = String(data: received, encoding: .utf8) else {
                finish(with: .failure(kind: .invalidFormat, detail: nil))
                return
            }
            finish(with: SubscriptionFetcher.parseBody(text))
        }
    }
}

private extension Data {
    init?(base64URL string: String) {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = base64.count % 4
        if padding > 0 {
            base64 += String(repeating: "=", count: 4 - padding)
        }
        self.init(base64Encoded: base64)
    }
}
