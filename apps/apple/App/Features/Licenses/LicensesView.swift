import AppCore
import SwiftUI

/// Settings > Open-source licenses: the components the app includes.
struct LicensesView: View {
    @State private var components: [OSSComponent] = []
    @State private var failed = false

    var body: some View {
        List {
            if failed {
                Text("Couldn't load the licenses.").foregroundStyle(.secondary)
            }
            ForEach(components) { component in
                NavigationLink { LicenseTextView(component: component) } label: {
                    VStack(alignment: .leading) {
                        Text(component.name).font(.body)
                        Text("\(component.version) · \(component.license)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .navigationTitle("Open-source licenses")
        .task {
            do { components = try OSSNotices.bundled() } catch { failed = true }
        }
    }
}

struct LicenseTextView: View {
    let component: OSSComponent

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("\(component.version) · \(component.license)")
                    .font(.subheadline).foregroundStyle(.secondary)
                if component.pending {
                    Text("The full license text is added with this component's first release.")
                        .font(.subheadline)
                }
                Text(verbatim: component.text)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(component.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}
