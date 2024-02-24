//
//  ContentView.swift
//  fileupload
//
//  Created by Hieu Vu on 24/02/2024.
//

import SwiftUI

struct ContentView: UIViewControllerRepresentable {

    let viewModel: UploadViewModel = RootComponent().getViewModel()

    func makeUIViewController(context: Context) -> UIViewController {
        return AppKt.AppIos(viewModel: viewModel)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

#Preview {
    ContentView()
}
