package de.maibornwolff.dependacharta.pipeline.analysis.analyzers

import de.maibornwolff.dependacharta.pipeline.analysis.analyzers.swift.SwiftAnalyzer
import de.maibornwolff.dependacharta.pipeline.analysis.model.FileInfo
import de.maibornwolff.dependacharta.pipeline.analysis.model.NodeType
import de.maibornwolff.dependacharta.pipeline.shared.SupportedLanguage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SwiftAnalyzerTest {
    @Test
    fun `extracts Swift declarations and used local types`() {
        val code = """
            import SwiftUI

            struct HomeComponent: View {
                let model: HomeComponentModel

                var body: some View {
                    EventCardView(event: model.event)
                }
            }

            protocol HomeComponentModelProtocol {}
        """.trimIndent()

        val report = SwiftAnalyzer(
            FileInfo(
                language = SupportedLanguage.SWIFT,
                physicalPath = "SMSTicket/Scenes/Home/HomeComponent.swift",
                content = code
            )
        ).analyze()

        assertThat(report.nodes).hasSize(2)
        assertThat(report.nodes.map { it.pathWithName.withDots() })
            .contains("SMSTicket.Scenes.Home.HomeComponent", "SMSTicket.Scenes.Home.HomeComponentModelProtocol")
        val homeComponent = report.nodes.first { it.name() == "HomeComponent" }
        assertThat(homeComponent.nodeType).isEqualTo(NodeType.VALUECLASS)
        assertThat(homeComponent.usedTypes.map { it.name })
            .contains("HomeComponentModel", "EventCardView")
            .doesNotContain("SwiftUI", "View")
    }

    @Test
    fun `scopes used types to the current declaration`() {
        val code = """
            nonisolated struct FeedEndpoint: ResponseEndpoint {
                typealias Response = HomeFeed
            }

            nonisolated struct AuthorizedFeedEndpoint: ResponseEndpoint, AuthorizedEndpoint {
                typealias Response = HomeFeed
            }
        """.trimIndent()

        val report = SwiftAnalyzer(
            FileInfo(
                language = SupportedLanguage.SWIFT,
                physicalPath = "SMSTicket/Networking/Endpoints/FeedEndpoint.swift",
                content = code
            )
        ).analyze()

        val feedEndpoint = report.nodes.first { it.name() == "FeedEndpoint" }
        val authorizedFeedEndpoint = report.nodes.first { it.name() == "AuthorizedFeedEndpoint" }

        assertThat(feedEndpoint.usedTypes.map { it.name })
            .contains("HomeFeed")
            .doesNotContain("AuthorizedEndpoint")
        assertThat(authorizedFeedEndpoint.usedTypes.map { it.name }).contains("HomeFeed")
    }

    @Test
    fun `uses file identity for extension files`() {
        val code = """
            nonisolated extension Endpoint {
                var isMocked: Bool {
                    switch self {
                    case is FeedEndpoint:
                        true
                    default:
                        false
                    }
                }
            }
        """.trimIndent()

        val report = SwiftAnalyzer(
            FileInfo(
                language = SupportedLanguage.SWIFT,
                physicalPath = "SMSTicket/Networking/Endpoint+Mocking.swift",
                content = code
            )
        ).analyze()

        assertThat(report.nodes.map { it.name() }).containsExactly("Endpoint+Mocking")
        val usedTypeNames = report.nodes
            .single()
            .usedTypes
            .map { it.name }
        assertThat(
            usedTypeNames
        ).contains("FeedEndpoint")
            .doesNotContain("Endpoint")
    }

    @Test
    fun `does not expose nested declarations as global dependency targets`() {
        val code = """
            final class ProductionLogService {
                struct Context: Sendable {
                    let requestId: String
                }
            }

            private struct GalleryPageView: UIViewControllerRepresentable {
                func makeUIViewController(context: Context) -> UIViewController {
                    UIViewController()
                }
            }
        """.trimIndent()

        val report = SwiftAnalyzer(
            FileInfo(
                language = SupportedLanguage.SWIFT,
                physicalPath = "SMSTicket/Services/LogService.swift",
                content = code
            )
        ).analyze()

        assertThat(report.nodes.map { it.name() })
            .contains("ProductionLogService", "GalleryPageView")
            .doesNotContain("Context")
    }
}
