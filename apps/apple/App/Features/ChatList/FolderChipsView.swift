import AppCore
import SwiftUI

/// All / Unread / custom folder chips under the title.
struct FolderChipsView: View {
    @Bindable var model: AppModel

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                ForEach(model.folders) { folder in
                    let selected = model.selectedFolder.id == folder.id
                    Button { model.selectedFolder = folder } label: {
                        Text(model.folderChipTitle(folder))
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .foregroundStyle(selected ? Color.white : Color.primary)
                            .background(Capsule().fill(selected ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("chatlist.chip.\(folder.id)")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
    }
}
