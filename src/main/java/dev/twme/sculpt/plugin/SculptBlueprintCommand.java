package dev.twme.sculpt.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.blueprint.BlueprintCommand;
import dev.twme.sculpt.util.MessageUtil;

/** Adapts {@code /sculpt blueprint} to {@link BlueprintCommand}. */
public final class SculptBlueprintCommand implements CommandExecutor, TabCompleter {

    private final BlueprintCommand delegate;

    public SculptBlueprintCommand(final Sculpt plugin) {
        this.delegate = new BlueprintCommand(plugin, plugin.getBlueprintManager());
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command cmd,
                             final String label, final String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.blueprint.player_only");
            return true;
        }
        // Prepend "blueprint" so BlueprintCommand sees ["blueprint", "sub", …]
        final String[] delegatedArgs;
        if (args.length == 0) {
            delegatedArgs = new String[]{"blueprint"};
        } else {
            delegatedArgs = new String[args.length + 1];
            delegatedArgs[0] = "blueprint";
            System.arraycopy(args, 0, delegatedArgs, 1, args.length);
        }
        return delegate.onCommand(sender, cmd, label, delegatedArgs);
    }

    @Override
    public java.util.List<String> onTabComplete(final CommandSender sender, final Command cmd,
                                                final String label, final String[] args) {
        final String[] delegatedArgs = new String[args.length + 1];
        delegatedArgs[0] = "blueprint";
        System.arraycopy(args, 0, delegatedArgs, 1, args.length);
        return delegate.onTabComplete(sender, cmd, label, delegatedArgs);
    }
}
