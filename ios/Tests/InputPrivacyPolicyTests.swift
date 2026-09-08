import UIKit
import XCTest
@testable import FuriganaKeyboard

/// The privacy promises the keyboard makes to the editor it is attached to.
final class InputPrivacyPolicyTests: XCTestCase {
    func testOrdinaryTextEditorsKeepEveryConversionFeature() {
        let policy = InputPrivacyPolicy(isSecureTextEntry: false)

        XCTAssertFalse(policy.isSensitiveField)
        XCTAssertTrue(policy.allowsDictionaryLookup)
        XCTAssertTrue(policy.allowsRecognitionContext)
        XCTAssertTrue(policy.allowsMarkedText)
        XCTAssertEqual(policy, .unrestricted)
    }

    func testSecureEditorsSuppressLookupContextAndComposition() {
        let policy = InputPrivacyPolicy(isSecureTextEntry: true)

        XCTAssertTrue(policy.isSensitiveField)
        XCTAssertFalse(policy.allowsDictionaryLookup)
        XCTAssertFalse(policy.allowsRecognitionContext)
        XCTAssertFalse(policy.allowsMarkedText)
    }

    func testAnUnansweredTraitIsTreatedAsAnOrdinaryEditor() {
        XCTAssertEqual(InputPrivacyPolicy(isSecureTextEntry: nil), .unrestricted)
        XCTAssertEqual(InputPrivacyPolicy(proxy: nil), .unrestricted)
    }
}
