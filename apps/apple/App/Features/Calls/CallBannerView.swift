import AppCore
import SwiftUI

/// Explains what the app did when the network changed.
struct CallBannerView: View {
    let banner: CallBanner

    private var symbol: String {
        switch banner {
        case .videoLowered, .audioOnly: "wifi.exclamationmark"
        case .reconnecting: "arrow.triangle.2.circlepath"
        case .connectionLost: "phone.down"
        }
    }

    var body: some View {
        Label(banner.text, systemImage: symbol)
            .font(.footnote.weight(.medium))
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassBackground(in: RoundedRectangle(cornerRadius: 16))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(banner.text))
            .accessibilityIdentifier("call.banner")
    }
}
