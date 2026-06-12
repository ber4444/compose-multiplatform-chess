import SwiftUI
import UIKit
import ChessApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(engine: StockfishChessEngine())
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea() // ChessApp applies WindowInsets.safeDrawing itself
    }
}
