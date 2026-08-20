package ca.deliyannides.dirtmcp.paper.command;

import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Result;
import ca.deliyannides.dirtmcp.paper.command.RunMinecraftCommands.Sender;
import ca.deliyannides.dirtmcp.paper.operation.OperationException;
import ca.deliyannides.dirtmcp.paper.operation.OperationFailure;
import java.util.List;
import java.util.Objects;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitCommandAccess implements PaperCommandService.PaperCommandAccess {
    private final JavaPlugin plugin;

    public BukkitCommandAccess(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public Result run(List<String> commands, int maxFeedbackCharacters) throws OperationException {
        if (!this.plugin.isEnabled()) {
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE, "The Dirt MCP plugin is not enabled");
        }
        Server server = this.plugin.getServer();
        CommandSender descriptionSender = server.createCommandSender(component -> {});
        if (!descriptionSender.isOp() || descriptionSender instanceof Player) {
            throw new OperationException(
                    OperationFailure.SERVER_UNAVAILABLE,
                    "Paper did not provide an operator-level non-player command sender");
        }
        Sender sender =
                new Sender(
                        descriptionSender.getName(),
                        descriptionSender.isOp(),
                        descriptionSender instanceof Player);
        return PaperCommandService.runBatch(
                commands,
                maxFeedbackCharacters,
                sender,
                (command, feedback) ->
                        server.dispatchCommand(server.createCommandSender(feedback), command));
    }
}
