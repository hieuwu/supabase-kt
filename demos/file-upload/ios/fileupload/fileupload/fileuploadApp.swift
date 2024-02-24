//
//  fileuploadApp.swift
//  fileupload
//
//  Created by Hieu Vu on 24/02/2024.
//

import SwiftUI
import common

@main
struct fileuploadApp: App {
    init() {
        KoinKt.doInitKoin(additionalConfiguration: {_ in})
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
