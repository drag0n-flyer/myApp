import SwiftUI

struct WelcomeView: View {
    let model: AppModel

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 14) {
                    Image(systemName: "circle.lefthalf.filled")
                        .font(.system(size: 40, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 84, height: 84)
                        .background(RoundedRectangle(cornerRadius: 22).fill(LinearGradient(colors: [.blue, .indigo], startPoint: .topLeading, endPoint: .bottomTrailing)))
                        .padding(.top, 56)
                        .accessibilityHidden(true)
                    Text("Welcome to \(AppBrand.name)")
                        .font(.largeTitle.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text("Clear calls and private chats, even on a weak network.")
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    VStack(alignment: .leading, spacing: 16) {
                        point("antenna.radiowaves.left.and.right", "Uses very little data", "Calls keep working on bad networks.")
                        point("lock", "Private by default", "Your personal chats stay between you and the people you talk to.")
                        point("house", "Your own server", "Join a server you trust, or run one yourself.")
                    }
                    .padding(.top, 20)
                }
                .padding(.horizontal, 24)
            }
            VStack(spacing: 8) {
                if model.onboarding.canCreateAccount {
                    Button("Get started") { model.onboarding.getStarted() }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("welcome.start")
                    Button("I already have an account") { model.onboarding.alreadyHaveAccount() }
                        .font(.headline)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("welcome.haveaccount")
                } else {
                    Button("Link to your iPhone") { model.onboarding.alreadyHaveAccount() }
                        .buttonStyle(PrimaryButtonStyle())
                        .accessibilityIdentifier("welcome.haveaccount")
                    Text("Create your account in \(AppBrand.name) on your iPhone first.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
        }
    }

    private func point(_ symbol: String, _ title: LocalizedStringKey, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(.tint).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}
