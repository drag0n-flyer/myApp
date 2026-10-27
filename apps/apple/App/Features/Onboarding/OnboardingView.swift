import AppCore
import SwiftUI

/// Shows the current sign-up step with a back button.
struct OnboardingView: View {
    @Bindable var model: AppModel

    var body: some View {
        ZStack(alignment: .topLeading) {
            step
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            if model.onboarding.step != .welcome {
                GlassCircleButton(systemImage: "chevron.backward", label: "Back") {
                    withAnimation { model.onboarding.back() }
                }
                .padding(.leading, 16)
                .padding(.top, 8)
            }
        }
        .animation(.default, value: model.onboarding.step)
    }

    @ViewBuilder private var step: some View {
        switch model.onboarding.step {
        case .welcome: WelcomeView(model: model)
        case .joinServer: JoinServerView(model: model)
        case .inviteCode: InviteCodeView(model: model)
        case .createAccount: CreateAccountView(model: model)
        case .verifyEmail: VerifyEmailView(model: model)
        case .createPasskey: PasskeyView(model: model)
        case .permissions: PermissionsView(model: model)
        case .linkDevice: LinkDeviceView(model: model)
        case .lostDevice: LostDeviceView(model: model)
        case .done: EmptyView()
        }
    }
}

/// Title, text, content, and a pinned primary button: the layout every step shares.
struct OnboardingPage<Content: View, Buttons: View>: View {
    let title: LocalizedStringKey
    var message: LocalizedStringKey?
    @ViewBuilder let content: Content
    @ViewBuilder let buttons: Buttons

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.title.weight(.bold))
                if let message {
                    Text(message).foregroundStyle(.secondary)
                }
                content.padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .padding(.top, 64)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) { buttons }
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
        }
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Capsule().fill(Color.accentColor.opacity(isEnabled ? 1 : 0.4)))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// Grouped input field used on sign-up pages.
struct OnboardingField<Field: View>: View {
    let label: LocalizedStringKey
    @ViewBuilder let field: Field

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            field
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Color.secondaryBackground, in: RoundedRectangle(cornerRadius: 12))
    }
}
