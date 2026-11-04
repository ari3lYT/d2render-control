# Configuration

The JVM system property `d2render.queue` selects the queue root. Default: `config/d2gate/render-queue`.
The directory name preserves compatibility with existing installations but does not require D2Gate.

Only trusted local services should have write access. Mount the queue into an external worker if
needed; do not grant that worker arbitrary access to the server's configuration or credentials.
The control mod does not implement renderer installation, NAS power management, delivery channels,
or a web service. Integrators implement these separately according to `docs/protocol.md`.
