package fr.codinbox.echo.api.local;

/**
 * The type of a node in the Echo network.
 *
 * <p>Each node in the network is either a {@link #PROXY} (e.g. a Velocity instance) or a
 * {@link #SERVER} (e.g. a Paper instance). This is configured via the {@code ECHO_RESOURCE_TYPE}
 * environment variable.</p>
 *
 * @see fr.codinbox.echo.api.EchoClient#getCurrentResourceType()
 */
public enum EchoResourceType {

    /**
     * A proxy node (e.g. Velocity). Proxies handle player connections and routing
     * between servers. They always perform healthcheck cleanup.
     */
    PROXY,

    /**
     * A backend server node (e.g. Paper). Servers host the actual game worlds
     * that players connect to through proxies.
     */
    SERVER,

}
