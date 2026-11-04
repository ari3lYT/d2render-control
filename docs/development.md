# Development

Use JDK 25 and `./gradlew build`. The source entry point is `ru.d2omg.render.RenderControl`.
JUnit covers coordinate bounds, area/video schemas, nonfinite and malformed input, unknown request
kinds, camera distances, duration limits and owner-console permissions. SpotBugs runs in `build`.

The mock worker is standard-library Python and has an independent protocol test. Run it only on
a disposable queue: it marks requests as errors with an explicit mock-worker explanation.
It performs no rendering, shell execution or remote delivery.

Runtime validation should cover queue visibility, permissions, atomic publication, worker progress,
cancellation during different stages, malformed status/result handling and an actual rendered artifact.
No production queue or private worker credentials are needed for CI.
