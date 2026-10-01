import Shared
import SwiftUI

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Group {
            if let loggedIn = model.session as? SessionState.LoggedIn {
                if model.setupPending {
                    SetupFlowView(firstName: loggedIn.user.firstName)
                } else {
                    ChatView(user: loggedIn.user)
                }
            } else if model.session is SessionState.LoggedOut {
                LoginFlowView()
            } else {
                ProgressView()
            }
        }
        .animation(.default, value: model.setupPending)
    }
}
