import CallLabKit
import SwiftUI

struct ConnectView: View {
    @EnvironmentObject var call: LabCall
    @AppStorage("lab.host") private var host = ""
    @AppStorage("lab.token") private var token = ""
    @AppStorage("lab.peer") private var peer = ""

    var body: some View {
        Form {
            Section("Server") {
                TextField("Host", text: $host)
                    .autocorrectionDisabled()
                    .accessibilityLabel("Server host")
                SecureField("Token (from the lab session command)", text: $token)
                    .accessibilityLabel("Session token")
                TextField("Peer account ID", text: $peer)
                    .autocorrectionDisabled()
                    .accessibilityLabel("Peer account ID")
            }
            KnobsForm(knobs: $call.knobs)
            Section {
                Button("Start call") {
                    call.configure(host: host, token: token)
                    Task { await call.start(peerAccountID: peer) }
                }
                .disabled(host.isEmpty || token.isEmpty || peer.isEmpty)
                .accessibilityLabel("Start call")
                Button("Wait for a call") {
                    call.configure(host: host, token: token)
                    Task { await call.listen() }
                }
                .disabled(host.isEmpty || token.isEmpty)
                .accessibilityLabel("Wait for an incoming call")
            }
            #if os(iOS)
            Section("Tools") {
                NavigationLink("Noise bench") { NSBenchView() }
            }
            #endif
            if let err = call.lastError {
                Section { Text(err).foregroundStyle(.red) }
            }
        }
        .navigationTitle("Call Lab")
        .navigationDestination(isPresented: $call.showCall) { CallView() }
    }
}
