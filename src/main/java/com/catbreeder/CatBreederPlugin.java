package com.catbreeder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import javax.inject.Inject;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;

@Slf4j
@PluginDescriptor(
		name = "Cat Breeder",
		description = "Detailed information for raising kittens",
		tags = { "kitten", "cat", "breeding", "raising", "timer" }
)
public class CatBreederPlugin extends Plugin
{
	@Inject
	private Notifier notifier;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Client client;

	@Inject
	private CatBreederConfig config;

	@Getter
	private boolean active;

	@Getter
	private KittenActivityTimer currentTimer;

	private final Gson gson = new Gson();
	private ItemContainer lastItemContainer;
	private String profileKey;
	private Kitten currentKitten;

	@Provides
	CatBreederConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CatBreederConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		currentKitten = new Kitten(client, this);
		profileKey = getProfileKey();
		//loadConfig();
		recheckActive();
	}

	@Override
	protected void shutDown() throws Exception
	{
		//saveConfig();
		removeTimer();
		currentTimer = null;
		active = false;
	}

	public void loadConfig()
	{
		configManager.unsetConfiguration("catBreeder", getProfileKey());
		profileKey = configManager.getRSProfileKey();

		Kitten kitten = gson.fromJson(
				configManager.getRSProfileConfiguration("catBreeder", "kitten"),
				new TypeToken<Kitten>(){}.getType());

		if (kitten != null)
		{
			currentKitten = kitten;
		}
	}

	public void saveConfig()
	{
		if (profileKey == null)
		{
			return;
		}
		configManager.setConfiguration("catBreeder", profileKey, "kitten", gson.toJson(currentKitten));
	}

	private void removeTimer()
	{
		infoBoxManager.removeInfoBox(currentTimer);
		currentTimer = null;
	}

	private void createTimer(Duration duration)
	{
		removeTimer();
		BufferedImage image = itemManager.getImage(ItemID.PET_KITTEN);
		currentTimer = new KittenActivityTimer(duration, image, this, active && config.displayTimer());
		infoBoxManager.addInfoBox(currentTimer);
		currentTimer.setVisible(active && config.displayTimer());
	}

	private void resetTimer(long seconds)
	{
		if (Instant.now().compareTo(currentTimer.getEndTime()) > seconds)
		{
			return;
		}
		createTimer(Duration.ofSeconds(seconds));
		log.info("Resetting timer.");
	}

	private void recheckActive()
	{
		checkAreaNpcs(client.getCachedNPCs());
		log.info("Rechecking area.");
	}

	private void reevaluateActive()
	{
		if (currentTimer != null)
		{
			currentTimer.setVisible(active && config.displayTimer());
		}
	}

	private void checkAreaNpcs(final NPC... npcs)
	{
		boolean foundKitten = false;

		for (NPC npc : npcs)
		{
			if (npc == null)
			{
				continue;
			}

			if (isNpcMatch(npc))
			{
				log.info("Found cat, enabling timer.");
				foundKitten = true;
				break;
			}
		}
		active = foundKitten;
		reevaluateActive();
	}

	private boolean isNpcMatch(NPC npc)
	{
		if (npc.getInteracting() == null)
		{
			return false;
		}
		return npc.getInteracting().equals(client.getLocalPlayer()) && Objects.requireNonNull(npc.getName()).contains("Kitten");
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Widget playerDialog = client.getWidget(WidgetInfo.DIALOG_PLAYER);
		if (playerDialog != null)
		{
			String dialogText = Text.removeTags(playerDialog.getText());
			switch (dialogText)
			{
				case Kitten.GAME_STROKE_MESSAGE:
					resetTimer(Kitten.ATTENTION_STROKE_TIME);
					break;
				case Kitten.GAME_FEED_MESSAGE:
					resetTimer(Kitten.ATTENTION_TIME_DEFAULT);
					break;
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		switch (event.getMessage())
		{
			case Kitten.GAME_FEED_MESSAGE:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Cat Breeder: " + Kitten.GAME_FEED_MESSAGE, null);
				resetTimer(Kitten.HUNGER_TIME);
				break;
			case Kitten.GAME_STROKE_MESSAGE:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Cat Breeder: " + Kitten.GAME_STROKE_MESSAGE, null);
				resetTimer(Kitten.ATTENTION_STROKE_TIME);
				break;
			case Kitten.GAME_ATTENTION_MESSAGE:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Cat Breeder: " + Kitten.GAME_ATTENTION_MESSAGE, null);
				// TODO: Send notification
				break;
			case Kitten.NPC_EXAMINE:
				event.getMessageNode().setRuneLiteFormatMessage("A friendly little pet. (Your kitten will grow up in: 20:00:00)");
				chatMessageManager.update(event.getMessageNode());
				// TODO: Update time left
				break;
		}
		reevaluateActive();
	}

	// TODO: Test
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ItemContainer itemContainer = client.getItemContainer(InventoryID.INVENTORY);

		if (itemContainer == null)
		{
			return;
		}

		for (Item item : itemContainer.getItems())
		{
			String itemName = itemManager.getItemComposition(item.getId()).getName();
			if (itemName.equalsIgnoreCase("null") || itemContainer == lastItemContainer)
			{
				continue;
			}
			lastItemContainer = itemContainer;
			log.info("Item: " + itemName + " (" + item.getQuantity() + ")");
		}
	}

	// TODO: Test
	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (event.getTarget() == null || event.getSource() != client.getLocalPlayer())
		{
			return;
		}
		recheckActive();
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		recheckActive();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.isConsumed())
		{
			return;
		}

		if (!event.getMenuTarget().contains("Ball of wool") || !event.getMenuTarget().contains("Kitten"))
		{
			return;
		}

		resetTimer(Kitten.ATTENTION_WOOL_TIME);
		reevaluateActive();
		log.info(event.getMenuTarget());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				recheckActive();
				break;
			case LOGGING_IN:
			case HOPPING:
			case CONNECTION_LOST:
				active = false;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		String key = event.getKey();
		if ("catShowTimer".equals(key) && currentTimer != null)
		{
			currentTimer.setVisible(active && config.displayTimer());
		}
		recheckActive();
	}

	public String getProfileKey()
	{
		StringBuilder key = new StringBuilder();
		EnumSet<WorldType> worldTypes = client.getWorldType();

		for (WorldType worldType : worldTypes)
		{
			if (worldTypes.contains(worldType))
			{
				key.append(worldType.name()).append(":");
			}

			if (client.getLocalPlayer() == null)
			{
				return "NULL PLAYER";
			}
		}
		key.append(Objects.requireNonNull(client.getLocalPlayer()).getName());
		return key.toString();
	}

	public CatBreederConfig getConfig()
	{
		return config;
	}
}
