package fr.codinbox.echo.api.server;

/**
 * Current game load reported by a server plugin.
 *
 * @param participantCount exact number of game participants
 * @param acceptingQueueAssignments whether Queue may assign another group
 */
public record ServerLoad(int participantCount, boolean acceptingQueueAssignments) {

    public ServerLoad {
        if (participantCount < 0)
            throw new IllegalArgumentException("participantCount must not be negative");
    }
}
