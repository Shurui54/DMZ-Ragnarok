package net.shurui.shuruisutilities.core.mixin.network;

import java.util.List;
import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shurui.shuruisutilities.util.events.world.SignEditEvent;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;

@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetHandler
{

    // post SignEditEvent so SU can inject custom colours into sign text. 1.20.1: signs carry front/back
    // SignText and the handler is updateSignText(packet, List<FilteredText>); rebuild a SignText from the lines
    // and apply via setText.
    // reach the player via the ServerPlayerConnection interface, NOT an @Shadow field: shadowed vanilla members
    // aren't remapped by the runtime refmap in this setup, but an interface cast resolves cleanly.
    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    public void updateSignText(ServerboundSignUpdatePacket packet, List<FilteredText> filteredText, CallbackInfo ci)
    {
        ServerPlayer player = ((ServerPlayerConnection) (Object) this).getPlayer();
        player.resetLastActionTime();
        ServerLevel serverworld = player.serverLevel();
        BlockPos blockpos = packet.getPos();
        if (serverworld.hasChunkAt(blockpos))
        {
            BlockState blockstate = serverworld.getBlockState(blockpos);
            BlockEntity tileentity = serverworld.getBlockEntity(blockpos);
            if (!(tileentity instanceof SignBlockEntity))
            {
                return;
            }
            SignBlockEntity signtileentity = (SignBlockEntity) tileentity;
            UUID editor = signtileentity.getPlayerWhoMayEdit();
            if (editor == null || !editor.equals(player.getUUID()))
            {
                LoggingHandler.sulog.warn("Player {} just tried to change non-editable sign", player.getDisplayName().getString());
                return;
            }
            ci.cancel();
            boolean front = packet.isFrontText();
            String[] lines = packet.getLines();
            SignEditEvent event = new SignEditEvent(packet.getPos(), lines, player);
            SignText signText = signtileentity.getText(front);
            if (MinecraftForge.EVENT_BUS.post(event))
            {
                for (int i = 0; i < lines.length; ++i)
                {
                    signText = signText.setMessage(i, event.formatted[i] == null ? Component.literal(lines[i]) : event.formatted[i]);
                }
            }
            else
            {
                for (int i = 0; i < lines.length; ++i)
                {
                    signText = signText.setMessage(i, Component.literal(lines[i]));
                }
            }
            signtileentity.setText(signText, front);
            signtileentity.setChanged();
            serverworld.sendBlockUpdated(blockpos, blockstate, blockstate, 3);
        }
    }

    // elevate the source to op level 4 when a PLAYER runs a chat command, so vanilla requires() predicates pass
    // and SU's permission system is the real gate (enforced later by ShuruisUtilities#commandEvent).
    // MixinCommandsG does this for performPrefixedCommand (console/command blocks/functions), but 1.20.1 parses
    // player chat commands here in parseCommand with the real un-elevated source, so without this a non-op with
    // an SU perm granted is still blocked by brigadier's op check. remap=false: parse() is a brigadier method.
    @Redirect(method = "parseCommand", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Ljava/lang/String;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;",
            remap = false))
    private ParseResults<CommandSourceStack> fe$elevatePlayerCommandSource(CommandDispatcher<CommandSourceStack> dispatcher, String command, Object source)
    {
        if (source instanceof CommandSourceStack css && css.getEntity() != null)
            source = css.withPermission(4);
        return dispatcher.parse(command, (CommandSourceStack) source);
    }

    // Same elevation for TAB COMPLETION. handleCustomCommandSuggestions parses the partial line with the player's
    // own un-elevated source and feeds it to getCompletionSuggestions, so a node whose requires() reads the source
    // (or, via MixinDmzCommandPermission, honours the source level) is skipped for a non-op and the client gets no
    // suggestions: the DMZ symptom is "will not auto complete". Elevate the same way parseCommand does so completion
    // reaches the argument nodes; the client tree (MixinCommands) and CommandExecutionGuard remain the real gate, and
    // suggestions are not execution, so this leaks nothing a non-op could run. This parse takes a StringReader, a
    // different overload from parseCommand's String one. remap=false: parse() is a brigadier method.
    @Redirect(method = "handleCustomCommandSuggestions", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Lcom/mojang/brigadier/StringReader;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;",
            remap = false))
    private ParseResults<CommandSourceStack> fe$elevateSuggestionSource(CommandDispatcher<CommandSourceStack> dispatcher, StringReader reader, Object source)
    {
        if (source instanceof CommandSourceStack css && css.getEntity() != null)
            source = css.withPermission(4);
        return dispatcher.parse(reader, (CommandSourceStack) source);
    }

    // The grave totem "open inside spawn protection" exemption used to live here as an @Redirect on the
    // ServerLevel#mayInteract call inside handleUseItemOn. It moved to a method level @Inject in
    // core.mixin.server.MixinServerLevelGraveInteract because Lootr @Redirects the same call site and two redirects on
    // one call site cannot coexist (that crashed ow1 on 2026-09-17). See that class for the full reasoning: do not
    // reintroduce a call site redirect here.

}
