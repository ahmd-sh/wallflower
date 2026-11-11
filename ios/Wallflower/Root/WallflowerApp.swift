import SwiftUI
import WallflowerDesign

@main
struct WallflowerApp: App {
    @Environment(\.scenePhase) private var phase
    @State private var model: AppModel

    init() {
        Fonts.register()
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .onChange(of: phase, initial: true) { _, p in
                    model.setAway(p != .active)
                    if p == .active && model.session != nil && !Rehearsal.dev { model.notices.ask() }
                }
        }
    }
}
