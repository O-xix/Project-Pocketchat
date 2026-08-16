import SwiftUI

// Phase 0 placeholder screen — recovery-menu-styled hello world.
// Replaced by the real terminal chat UI in Phase 4.
struct ContentView: View {
    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.black.ignoresSafeArea()
            Text("pocketchat> hello world_")
                .font(.system(size: 17, design: .monospaced))
                .foregroundColor(Color(red: 0.2, green: 1.0, blue: 0.4))
                .padding(24)
        }
    }
}

#Preview {
    ContentView()
}
