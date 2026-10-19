import XCTest
@testable import TeveeStreaming

/// All identities, metadata and image bytes below are synthetic test fixtures.
final class LibraryDatabaseTests: XCTestCase {
    private func folder() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }
    private func video(_ chat: Int64 = 1) -> TelegramVideo {
        TelegramVideo(messageID: 7, chatID: chat, fileID: 9, title: "Synthetic \"title\"; ' video", size: 200, mimeType: "video/mp4", duration: 120, localPath: "/synthetic/cache", downloadedBytes: 200, isDownloaded: true)
    }

    func testMetadataSelectionAndHistorySurviveReopening() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("library.sqlite")
        let first = LibraryDatabase(url: url)
        let item = video()
        try await first.save(collection: TelegramChat(id: 1, title: "Synthetic collection", type: "chatTypePrivate"), selectedByDefault: true)
        try await first.save(videos: [item])
        try await first.save(progress: WatchProgress(videoID: item.id, position: 32, duration: 120), video: item)
        let reopened = LibraryDatabase(url: url)
        let result = try await reopened.snapshot()
        XCTAssertEqual(result.videos, [item])
        XCTAssertEqual(result.selected, [1])
        XCTAssertEqual(result.history[item.id]?.position, 32)
        XCTAssertEqual(result.history[item.id]?.canResume, true)
    }

    func testRefreshPreservesDeselectedCollectionsAndWatchHistory() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        let db = LibraryDatabase(url: directory.appendingPathComponent("library.sqlite"))
        let item = video()
        let chat = TelegramChat(id: 1, title: "Synthetic", type: "chatTypePrivate")
        try await db.save(collection: chat, selectedByDefault: true)
        try await db.select(collectionID: 1, selected: false)
        try await db.save(collection: chat, selectedByDefault: true)
        try await db.save(progress: WatchProgress(videoID: item.id, position: 30, duration: 120), video: item)
        try await db.save(videos: [item])
        let result = try await db.snapshot()
        XCTAssertTrue(result.selected.isEmpty)
        XCTAssertEqual(result.history[item.id]?.position, 30)
    }

    func testCacheInvalidationKeepsLibraryAndHistory() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        let db = LibraryDatabase(url: directory.appendingPathComponent("library.sqlite"))
        let item = video()
        try await db.save(progress: WatchProgress(videoID: item.id, position: 30, duration: 120), video: item)
        try await db.invalidateMediaCache()
        let result = try await db.snapshot()
        XCTAssertEqual(result.videos.count, 1)
        XCTAssertFalse(result.videos[0].isDownloaded)
        XCTAssertEqual(result.videos[0].localPath, "")
        XCTAssertEqual(result.history[item.id]?.position, 30)
    }

    func testAccountDatabasesAndChatScopedHistoryAreIndependent() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        let first = LibraryDatabase(url: directory.appendingPathComponent("1/library.sqlite"))
        let second = LibraryDatabase(url: directory.appendingPathComponent("2/library.sqlite"))
        let a = video(1), b = video(2)
        try await first.save(progress: WatchProgress(videoID: a.id, position: 20, duration: 120), video: a)
        try await first.save(progress: WatchProgress(videoID: b.id, position: 40, duration: 120), video: b)
        try await first.clearHistory(videoID: a.id)
        let firstResult = try await first.snapshot()
        let secondResult = try await second.snapshot()
        XCTAssertNil(firstResult.history[a.id])
        XCTAssertEqual(firstResult.history[b.id]?.position, 40)
        XCTAssertTrue(secondResult.videos.isEmpty)
        XCTAssertTrue(secondResult.history.isEmpty)
    }

    func testInvalidDatabaseIsNotSilentlyOverwritten() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("library.sqlite")
        let original = Data("synthetic damaged database".utf8)
        try original.write(to: url)
        do { _ = try await LibraryDatabase(url: url).snapshot(); XCTFail("Expected failure") } catch { }
        XCTAssertEqual(try Data(contentsOf: url), original)
    }

    func testDelayedOlderProgressCannotOverwriteNewerPosition() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        let db = LibraryDatabase(url: directory.appendingPathComponent("library.sqlite"))
        let item = video()
        let now = Date()
        try await db.save(progress: WatchProgress(videoID: item.id, position: 75, duration: 120, updatedAt: now), video: item)
        try await db.save(progress: WatchProgress(videoID: item.id, position: 20, duration: 120, updatedAt: now.addingTimeInterval(-5)), video: item)
        let result = try await db.snapshot()
        XCTAssertEqual(result.history[item.id]?.position, 75)
    }

    func testBatchCollectionRefreshKeepsSelectionAndAdvancesRevision() async throws {
        let directory = folder(); defer { try? FileManager.default.removeItem(at: directory) }
        let db = LibraryDatabase(url: directory.appendingPathComponent("library.sqlite"))
        let chat = TelegramChat(id: 1, title: "Synthetic", type: "chatTypePrivate")
        try await db.save(collection: chat, selectedByDefault: true)
        let before = try await db.snapshot()
        try await db.save(collections: [TelegramChat(id: 1, title: "Renamed", type: chat.type), TelegramChat(id: 2, title: "Other", type: chat.type)])
        let result = try await db.snapshot()
        XCTAssertEqual(result.selected, [1])
        XCTAssertEqual(result.collections.count, 2)
        XCTAssertEqual(result.collections.first(where: { $0.id == 1 })?.title, "Renamed")
        XCTAssertGreaterThan(result.revision, before.revision)
    }

    func testThumbnailMetadataAndLocalStateRoundTrip() throws {
        let message: [String: Any] = ["id": Int64(7), "date": Int64(1234), "content": ["video": [
            "video": ["id": Int32(9), "size": Int64(200)], "duration": 120,
            "thumbnail": ["format": ["@type": "thumbnailFormatJpeg"], "file": ["id": Int32(10), "remote": ["id": "synthetic-remote", "unique_id": "synthetic-unique"]]],
            "minithumbnail": ["data": Data([1, 2, 3]).base64EncodedString()]
        ]]]
        let item = try XCTUnwrap(TelegramVideo.from(message: message, chatID: 1))
        XCTAssertEqual(item.thumbnailFileID, 10)
        XCTAssertEqual(item.thumbnailRemoteID, "synthetic-remote")
        XCTAssertEqual(item.thumbnailUniqueID, "synthetic-unique")
        XCTAssertEqual(item.minithumbnail, Data([1, 2, 3]))
        XCTAssertEqual(item.date, 1234)
        let reopened = try JSONDecoder().decode(TelegramVideo.self, from: JSONEncoder().encode(item))
        XCTAssertEqual(reopened, item)
        let updated = item.updating(file: ["id": Int32(9), "size": Int64(200), "local": ["path": "/synthetic/new", "downloaded_size": Int64(200), "is_downloading_completed": true]])
        XCTAssertTrue(updated.isDownloaded)
        XCTAssertEqual(updated.localPath, "/synthetic/new")
        XCTAssertEqual(updated.thumbnailFileID, 10)
    }

    func testProgressNormalizationAndCompletion() {
        XCTAssertEqual(WatchProgress(videoID: "1:7", position: .nan, duration: .infinity).position, 0)
        XCTAssertFalse(WatchProgress(videoID: "1:7", position: 4, duration: 100).canResume)
        XCTAssertTrue(WatchProgress(videoID: "1:7", position: 30, duration: 100).canResume)
        XCTAssertTrue(WatchProgress(videoID: "1:7", position: 96, duration: 100).completed)
        XCTAssertFalse(WatchProgress(videoID: "1:7", position: 96, duration: 100).canResume)
    }
}

final class ThumbnailCacheTests: XCTestCase {
    func testCacheRoundTripBudgetAndClearPreserveUnrelatedFiles() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = ThumbnailCache(directory: folder, budget: 8)
        try await cache.write(Data([1, 2, 3, 4, 5]), key: "1_2_3")
        let first = try await cache.read("1_2_3")
        XCTAssertEqual(first, Data([1, 2, 3, 4, 5]))
        try await cache.write(Data([6, 7, 8, 9, 10]), key: "1_2_4")
        let bytes = try await cache.bytes()
        XCTAssertLessThanOrEqual(bytes, 8)
        let unrelated = folder.appendingPathComponent("keep.txt")
        try Data([42]).write(to: unrelated)
        try await cache.clear()
        let emptyBytes = try await cache.bytes()
        XCTAssertEqual(emptyBytes, 0)
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
    }

    func testTraversalAndOversizeWritesAreRejected() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let cache = ThumbnailCache(directory: folder, budget: 4)
        for key in ["../session", "/tmp/secret", "a/b", ""] {
            do { try await cache.write(Data([1]), key: key); XCTFail("Expected rejection") } catch { }
        }
        do { try await cache.write(Data(repeating: 0, count: 5), key: "1_2_3"); XCTFail("Expected rejection") } catch { }
    }
}
