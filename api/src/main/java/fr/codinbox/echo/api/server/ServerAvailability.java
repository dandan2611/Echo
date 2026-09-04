package fr.codinbox.echo.api.server;

/**
 * Whether a live server accepts new player assignments.
 */
public enum ServerAvailability {
    /** Accepts new player assignments. */
    ACTIVE,

    /** Remains alive for existing players but rejects new assignments. */
    DRAINING
}
