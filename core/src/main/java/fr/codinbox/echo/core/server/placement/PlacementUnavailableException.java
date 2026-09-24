package fr.codinbox.echo.core.server.placement;

/** Technical failure, distinct from a successfully evaluated capacity refusal. */
public final class PlacementUnavailableException extends IllegalStateException {
    public PlacementUnavailableException(String message) {
        super(message);
    }
}
