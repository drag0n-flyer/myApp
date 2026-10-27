import Foundation

private struct Empty: Codable {}
private struct Available: Decodable { let available: Bool }
private struct Valid: Decodable { let valid: Bool }
private struct SignupID: Codable { let signupID: String }
private struct DeviceList: Decodable { let devices: [Device] }

private func pathSegment(_ s: String) -> String {
    s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? s
}

extension ServerClient {
    // MARK: Sign-up

    public func usernameAvailable(_ name: String) async throws(ServerError) -> Bool {
        let r: Available = try await request("GET", "/v1/usernames/\(pathSegment(name))", auth: .none)
        return r.available
    }

    public func checkInvite(code: String) async throws(ServerError) -> Bool {
        struct Body: Encodable { let code: String }
        let r: Valid = try await request("POST", "/v1/invites/check", body: Body(code: code), auth: .none)
        return r.valid
    }

    public func signupStart(inviteCode: String?, username: String, email: String) async throws(ServerError) -> String {
        struct Body: Encodable { let inviteCode: String?; let username: String; let email: String }
        let r: SignupID = try await request("POST", "/v1/signup/start",
                                            body: Body(inviteCode: inviteCode, username: username, email: email), auth: .none)
        return r.signupID
    }

    public func signupVerify(signupID: String, code: String) async throws(ServerError) {
        struct Body: Encodable { let signupID: String; let code: String }
        try await requestNoContent("POST", "/v1/signup/verify", body: Body(signupID: signupID, code: code), auth: .none)
    }

    public func signupPasskeyOptions(signupID: String) async throws(ServerError) -> CreationOptions {
        try await request("POST", "/v1/signup/passkey/options", body: SignupID(signupID: signupID), auth: .none)
    }

    public func signupFinish(signupID: String, credential: RegistrationCredential?, deviceName: String,
                             platform: String) async throws(ServerError) -> NewSession {
        struct Body: Encodable {
            let signupID: String; let credential: RegistrationCredential?; let deviceName: String; let platform: String
        }
        return try await request("POST", "/v1/signup/finish",
                                 body: Body(signupID: signupID, credential: credential, deviceName: deviceName, platform: platform),
                                 auth: .none)
    }

    // MARK: Sign-in

    public func signinPasskeyOptions() async throws(ServerError) -> (challengeID: String, options: RequestOptions) {
        struct Reply: Decodable { let challengeID: String; let options: RequestOptions }
        let r: Reply = try await request("POST", "/v1/signin/passkey/options", body: Empty(), auth: .none)
        return (r.challengeID, r.options)
    }

    public func signinPasskeyFinish(challengeID: String, credential: AssertionCredential, deviceName: String,
                                    platform: String) async throws(ServerError) -> NewSession {
        struct Body: Encodable {
            let challengeID: String; let credential: AssertionCredential; let deviceName: String; let platform: String
        }
        return try await request("POST", "/v1/signin/passkey/finish",
                                 body: Body(challengeID: challengeID, credential: credential, deviceName: deviceName, platform: platform),
                                 auth: .none)
    }

    public func signinEmailStart(email: String) async throws(ServerError) {
        struct Body: Encodable { let email: String }
        try await requestNoContent("POST", "/v1/signin/email/start", body: Body(email: email), auth: .none)
    }

    public func signinEmailFinish(email: String, code: String, deviceName: String,
                                  platform: String) async throws(ServerError) -> NewSession {
        struct Body: Encodable { let email: String; let code: String; let deviceName: String; let platform: String }
        return try await request("POST", "/v1/signin/email/finish",
                                 body: Body(email: email, code: code, deviceName: deviceName, platform: platform), auth: .none)
    }

    // MARK: Account

    public func addPasskeyOptions() async throws(ServerError) -> CreationOptions {
        try await request("POST", "/v1/me/passkeys/options", body: Empty(), auth: .session)
    }

    public func addPasskey(_ credential: RegistrationCredential) async throws(ServerError) {
        struct Body: Encodable { let credential: RegistrationCredential }
        try await requestNoContent("POST", "/v1/me/passkeys", body: Body(credential: credential), auth: .session)
    }

    public func me() async throws(ServerError) -> Me {
        try await request("GET", "/v1/me", auth: .session)
    }

    public func deleteMe() async throws(ServerError) {
        try await requestNoContent("DELETE", "/v1/me", auth: .session)
    }

    public func devices() async throws(ServerError) -> [Device] {
        let r: DeviceList = try await request("GET", "/v1/devices", auth: .session)
        return r.devices
    }

    public func revokeDevice(id: String) async throws(ServerError) {
        try await requestNoContent("DELETE", "/v1/devices/\(pathSegment(id))", auth: .session)
    }

    // MARK: Linking

    public func linkStart(deviceName: String, platform: String) async throws(ServerError) -> LinkTicket {
        struct Body: Encodable { let deviceName: String; let platform: String }
        return try await request("POST", "/v1/link", body: Body(deviceName: deviceName, platform: platform), auth: .none)
    }

    /// Approving device: what the new device is called, before approving.
    public func linkInfo(linkID: String, secret: String) async throws(ServerError) -> LinkInfo {
        struct Body: Encodable { let secret: String }
        return try await request("POST", "/v1/link/\(pathSegment(linkID))/info", body: Body(secret: secret), auth: .session)
    }

    public func linkApprove(linkID: String, secret: String, sealed: Data?) async throws(ServerError) {
        struct Body: Encodable { let secret: String; let sealed: Base64URLData? }
        try await requestNoContent("POST", "/v1/link/\(pathSegment(linkID))/approve",
                                   body: Body(secret: secret, sealed: sealed.map { Base64URLData($0) }), auth: .session)
    }

    public func linkClaim(linkID: String, claimToken: String) async throws(ServerError) -> LinkClaim {
        struct Body: Encodable { let claimToken: String }
        struct Approved: Decodable {
            let accountID: String; let deviceID: String; let sessionToken: String; let sealed: Base64URLData?
        }
        let body: Data
        do { body = try ServerJSON.encoder().encode(Body(claimToken: claimToken)) } catch { throw ServerError.decoding() }
        let (data, response) = try await requestRaw("POST", "/v1/link/\(pathSegment(linkID))/claim", body: body,
                                                    contentType: "application/json", headers: [:], auth: .none)
        if response.statusCode == 202 { return .pending }
        do {
            let a = try ServerJSON.decoder().decode(Approved.self, from: data)
            return .approved(NewSession(accountID: a.accountID, deviceID: a.deviceID, sessionToken: a.sessionToken),
                             sealed: a.sealed?.data)
        } catch { throw ServerError.decoding() }
    }
}
