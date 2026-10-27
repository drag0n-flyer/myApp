import AppCore
import SwiftUI

/// Settings > Open-source licenses: the components the app includes.
/// Real component names (for example "mls-rs") may appear on this legal notices page only: the
/// licenses require them, so the plain-words rule does not apply here.
struct LicensesView: View {
    @State private var book: OSSNoticeBook?
    @State private var failed = false

    var body: some View {
        List {
            if failed {
                Text("Couldn't load the licenses.").foregroundStyle(.secondary)
            }
            ForEach(book?.components ?? []) { component in
                NavigationLink { LicenseTextView(component: component, text: book?.text(for: component) ?? "") } label: {
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
            do { book = try OSSNotices.bundled() } catch { failed = true }
        }
    }
}

struct LicenseTextView: View {
    let component: OSSComponent
    let text: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("\(component.version) · \(component.license)")
                    .font(.subheadline).foregroundStyle(.secondary)
                if !component.authors.isEmpty {
                    Text(verbatim: component.authors).font(.subheadline)
                }
                if let repository = component.repository {
                    Text(verbatim: repository).font(.subheadline).textSelection(.enabled)
                }
                if component.pending {
                    Text("The full license text is added with this component's first release.")
                        .font(.subheadline)
                }
                Text(verbatim: text)
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
