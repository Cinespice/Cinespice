import SwiftUI
import UIKit

enum TeveeStyle {
    static let background = Color(red: 0.035, green: 0.035, blue: 0.035)
    static let surface = Color(red: 0.075, green: 0.085, blue: 0.095)
    static let accent = Color(red: 1, green: 0.35, blue: 0.15)
    static let muted = Color.white.opacity(0.48)
}

/// Keep the visual label and its hit area together. Opacity/background feedback
/// does not move or shrink the target under the user's finger.
struct CinespiceButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var onPressChanged: (Bool) -> Void = { _ in }

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .background(configuration.isPressed && isEnabled ? Color.white.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 10))
            .opacity(!isEnabled ? 0.35 : (configuration.isPressed ? 0.65 : 1))
            .onChange(of: configuration.isPressed, perform: onPressChanged)
    }
}

struct VideoArtwork: View {
    @EnvironmentObject private var telegram: TelegramClient
    @EnvironmentObject private var library: LibraryStore
    let video: TelegramVideo
    @State private var image: UIImage?
    @State private var failed = false
    private static let memory: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>(); cache.countLimit = 80; cache.totalCostLimit = 32 * 1024 * 1024; return cache
    }()
    static func clearMemory() { memory.removeAllObjects() }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                TeveeStyle.surface
                if let image {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped()
                } else {
                    LinearGradient(colors: [Color.white.opacity(0.05), .clear], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Image(systemName: failed ? "photo" : "film")
                        .font(.system(size: 28, weight: .ultraLight)).foregroundStyle(Color.white.opacity(0.18))
                }
            }
        }
        .clipped()
        .contentShape(Rectangle())
        .allowsHitTesting(false)
        .accessibilityLabel(failed ? "Artwork unavailable" : "Video artwork")
        .task(id: "\(library.accountID ?? 0)_\(video.thumbnailKey)_\(library.artworkGeneration)") {
            image = nil; failed = false
            let key = "\(library.accountID ?? 0)_\(video.thumbnailKey)" as NSString
            if let cached = Self.memory.object(forKey: key) { image = cached; return }
            if let mini = video.minithumbnail { image = UIImage(data: mini) }
            do {
                guard let data = try await telegram.thumbnail(video) else { return }
                try Task.checkCancellation()
                guard let decoded = UIImage(data: data) else { failed = true; return }
                image = decoded
                Self.memory.setObject(decoded, forKey: key, cost: decoded.cgImage.map { $0.bytesPerRow * $0.height } ?? data.count * 4)
            } catch is CancellationError { }
            catch { failed = true }
        }
    }
}

struct VideoCard: View {
    @EnvironmentObject private var telegram: TelegramClient
    @EnvironmentObject private var library: LibraryStore
    let video: TelegramVideo
    var wide = false
    var opening = false
    @State private var savingHistory = false
    let play: (Bool) -> Void
    let download: () -> Void
    let export: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Button { play(true) } label: {
                ZStack(alignment: .bottomLeading) {
                    VideoArtwork(video: video)
                    LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .center, endPoint: .bottom)
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(video.formatLabel).font(.system(size: 8, weight: .bold)).padding(4).background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 3))
                            if library.display(video).isDownloaded { Image(systemName: "arrow.down.circle.fill").accessibilityLabel("Downloaded") }
                            Spacer()
                            if video.duration > 0 { Text(duration(video.duration)).font(.system(size: 10, weight: .semibold, design: .monospaced)) }
                        }
                        if let progress = library.snapshot.history[video.id], progress.canResume {
                            ProgressView(value: progress.fraction).tint(TeveeStyle.accent).background(Color.white.opacity(0.2))
                        }
                    }.padding(12)
                    if opening { ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
                }
                .aspectRatio(wide ? 16 / 9 : 0.73, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .contentShape(Rectangle())
            }.buttonStyle(CinespiceButtonStyle()).accessibilityLabel("Play \(video.title)")
            HStack(alignment: .top, spacing: 5) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(video.title).font(.system(size: 13, weight: .semibold)).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                    if telegram.preparingDownloads.contains(video.id) {
                        ProgressView("Preparing download…").font(.caption2)
                    } else if telegram.cancellingDownloads.contains(video.fileID) {
                        ProgressView("Cancelling download…").font(.caption2)
                    } else if telegram.fullDownloads.contains(video.fileID) {
                        Text("Downloading · \(ByteCountFormatter.string(fromByteCount: library.display(video).downloadedBytes, countStyle: .file))").font(.caption2).foregroundStyle(TeveeStyle.accent)
                    }
                    if savingHistory { ProgressView("Saving history…").font(.caption2) }
                    else if library.snapshot.history[video.id]?.completed == true { Text("Watched").font(.caption2).foregroundStyle(TeveeStyle.accent) }
                    Text(library.snapshot.collections.first(where: { $0.id == video.chatID })?.title ?? "Telegram")
                        .font(.system(size: 10)).foregroundStyle(TeveeStyle.muted).lineLimit(1)
                }
                Menu {
                    Button("Play / resume") { play(true) }
                    Button("Play from beginning") { play(false) }
                    if telegram.fullDownloads.contains(video.fileID) {
                        Button("Cancel download") { Task { await telegram.cancelDownload(video) } }.disabled(telegram.cancellingDownloads.contains(video.fileID))
                    } else { Button("Download first") { download() }.disabled(telegram.preparingDownloads.contains(video.id)) }
                    if library.display(video).isDownloaded { Button("Save to Files…", action: export) }
                    Button("Mark watched") { updateHistory(remove: false) }.disabled(savingHistory)
                    if library.snapshot.history[video.id] != nil {
                        Button("Remove from watch history") { updateHistory(remove: true) }.disabled(savingHistory)
                    }
                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44).contentShape(Rectangle()) }
                .buttonStyle(CinespiceButtonStyle())
                .accessibilityLabel("Options for \(video.title)")
            }
        }.foregroundStyle(.white)
    }
    private func updateHistory(remove: Bool) {
        guard !savingHistory else { return }; savingHistory = true
        Task {
            defer { savingHistory = false }
            if remove { _ = await library.clearHistory(videoID: video.id) }
            else { await library.record(video, position: Double(video.duration), duration: Double(video.duration), completed: true) }
        }
    }
    private func duration(_ seconds: Int) -> String { seconds >= 3600 ? "\(seconds / 3600)h \(seconds / 60 % 60)m" : "\(seconds / 60)m" }
}

struct LibraryEmptyState: View {
    let symbol: String
    let title: String
    let detail: String
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: symbol).font(.system(size: 36, weight: .ultraLight)).foregroundStyle(TeveeStyle.accent)
            Text(title).font(.title3.weight(.semibold))
            Text(detail).font(.subheadline).foregroundStyle(TeveeStyle.muted).multilineTextAlignment(.center).lineSpacing(4)
        }.frame(maxWidth: .infinity).padding(.horizontal, 32).padding(.vertical, 60)
    }
}

struct VideoDetailsView: View {
    @Environment(\.dismiss) private var dismiss
    let video: TelegramVideo
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VideoArtwork(video: video).aspectRatio(16 / 9, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 10))
                    Text(video.fileName ?? video.title).font(.title2.weight(.bold)).textSelection(.enabled)
                    HStack { Text(video.formatLabel); Text("·"); Text(ByteCountFormatter.string(fromByteCount: video.size, countStyle: .file)) }.font(.subheadline).foregroundStyle(TeveeStyle.muted)
                    if video.date > 0 { Text(Date(timeIntervalSince1970: Double(video.date)), style: .date).font(.caption).foregroundStyle(TeveeStyle.muted) }
                    if let caption = video.caption { Text(caption).font(.body).textSelection(.enabled) }
                    Text("File details and artwork are cached on this iPhone. Open a channel's menu to refresh changed metadata.").font(.caption).foregroundStyle(TeveeStyle.muted)
                }.padding(22)
            }.background(TeveeStyle.background).navigationTitle("File details").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .navigationBarTrailing) { Button("Done") { dismiss() } } }
        }.navigationViewStyle(.stack).preferredColorScheme(.dark)
    }
}

/// Geometry gates pagination even when an enclosing VStack is laid out eagerly.
struct LibraryPageTrigger: View {
    let enabled: Bool
    let revision: String
    let viewportHeight: CGFloat
    let action: () -> Void
    var body: some View {
        GeometryReader { geometry in
            let y = geometry.frame(in: .named("library-scroll")).minY
            Color.clear.allowsHitTesting(false).task(id: "\(enabled)_\(revision)_\(Int(y / 100))") {
                guard enabled, y >= -100, y < viewportHeight + 180 else { return }
                action()
            }
        }.frame(height: 1)
    }
}

/// Acknowledges every play tap before Telegram or disk work finishes. Blocks
/// duplicate taps but keeps cancellation available, including with VoiceOver.
struct MediaOpeningOverlay: View {
    let title: String
    let cancel: () -> Void
    var body: some View {
        ZStack {
            Color.black.opacity(0.65).ignoresSafeArea().contentShape(Rectangle())
            VStack(spacing: 16) {
                ProgressView().tint(TeveeStyle.accent).scaleEffect(1.3).padding(8)
                Text("Loading media…").font(.headline)
                Text("Warming up your cinema.").font(.subheadline).foregroundStyle(.secondary)
                Text(title).font(.caption).lineLimit(2).multilineTextAlignment(.center)
                Button(action: cancel) {
                    Text("Cancel").font(.subheadline.weight(.semibold))
                        .frame(minWidth: 100, minHeight: 44)
                        .background(.white.opacity(0.1), in: Capsule())
                }.buttonStyle(CinespiceButtonStyle())
            }.padding(28).frame(maxWidth: 330)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24))
                .padding(24).accessibilityElement(children: .contain)
        }.transition(.opacity)
    }
}
