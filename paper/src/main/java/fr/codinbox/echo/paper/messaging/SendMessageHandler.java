package fr.codinbox.echo.paper.messaging;

import fr.codinbox.echo.api.messaging.EchoMessage;
import fr.codinbox.echo.api.messaging.MessageHandler;
import fr.codinbox.echo.api.messaging.impl.SendMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class SendMessageHandler implements MessageHandler {

    @Override
    public void onReceive(final @NotNull EchoMessage message) {
        if (message instanceof SendMessage sm) {
            final Component component = sm.getComponent();

            for (UUID target : sm.getTargets()) {
                final Player player = Bukkit.getPlayer(target);
                if (player == null)
                    continue;
                player.sendMessage(component);
            }
        }
    }

}
