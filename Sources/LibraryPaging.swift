import Foundation

/// Persisted independently for every selected collection. Each user action reads
/// at most 30 results per video/document filter; unrelated documents are discarded.
struct CollectionIndex: Codable, Equatable, Sendable {
    static let filters = ["searchMessagesFilterVideo", "searchMessagesFilterDocument"]
    static let pageLimit = 30
    var cursors: [String: Int64] = Dictionary(uniqueKeysWithValues: filters.map { ($0, 0) })
    var updatedAt: Date = Date()
    var hasMore: Bool { Self.filters.contains { cursors[$0] != nil } }

    mutating func advance(filter: String, from: Int64, next: Int64) {
        if next == 0 || next == from { cursors.removeValue(forKey: filter) }
        else { cursors[filter] = next }
        updatedAt = Date()
    }
}

enum LibraryOrder: String, CaseIterable {
    case oldest, newest
    var title: String { self == .oldest ? "Oldest first" : "Newest first" }
    func sort(_ videos: [TelegramVideo]) -> [TelegramVideo] {
        videos.sorted {
            if $0.date != $1.date { return self == .oldest ? $0.date < $1.date : $0.date > $1.date }
            if $0.chatID != $1.chatID { return $0.chatID < $1.chatID }
            return self == .oldest ? $0.messageID < $1.messageID : $0.messageID > $1.messageID
        }
    }
}
