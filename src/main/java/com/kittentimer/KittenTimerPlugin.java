/*
 * Copyright (c) 2021, xVye
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.kittentimer;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.inject.Inject;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WorldChanged;
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
import net.runelite.client.ui.overlay.infobox.Timer;
import net.runelite.client.util.Text;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Slf4j
@PluginDescriptor(
	name = "Kitten Timer",
	description = "Detailed information for raising kittens",
	tags = {"kitten", "cat", "breeding", "raising", "timer"}
)
public class KittenTimerPlugin extends Plugin
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
	private KittenTimerConfig config;

	@Getter
	private boolean active;

	@Getter
	private KittenAttentionTimer currentTimer;

	@Getter
	private Kitten currentKitten;

	private final Gson gson = new Gson();
	private final List<Timer> activityTimers = new ArrayList<>();
	private ItemContainer lastItemContainer;
	private String profileKey;

	@Provides
	KittenTimerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(KittenTimerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		profileKey = getProfileKey();
		//loadConfig();
		recheckActive();
	}

	@Override
	protected void shutDown() throws Exception
	{
		//saveConfig();
		removeTimers();
		currentTimer = null;
		active = false;
	}

	public void loadConfig()
	{
		configManager.unsetConfiguration("kittenTimer", getProfileKey());
		profileKey = configManager.getRSProfileKey();

		Kitten kitten = gson.fromJson(
			configManager.getRSProfileConfiguration("kittenTimer", "kitten"),
			new TypeToken<Kitten>()
			{
			}.getType());

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
		configManager.setConfiguration("kittenTimer", profileKey, "kitten", gson.toJson(currentKitten));
	}

	private void createTimer(Duration duration)
	{
		boolean followerActive = client.getVarpValue(KittenVarPlayer.FOLLOWER.getId()) != -1;
		if (!followerActive)
		{
			return;
		}

		removeTimers();
		BufferedImage image = itemManager.getImage(ItemID.PET_KITTEN);
		currentTimer = new KittenAttentionTimer(duration, image, this, active && config.displayAttentionTimer());
		infoBoxManager.addInfoBox(currentTimer);
		currentTimer.setVisible(active && config.displayAttentionTimer());
	}

	private void createTimers()
	{
		createTimers(ItemID.PET_KITTEN);
	}

	private void createTimers(int iconID)
	{
		removeTimers();

		boolean followerActive = client.getVarpValue(KittenVarPlayer.FOLLOWER.getId()) != -1;
		if (!followerActive)
		{
			return;
		}

		BufferedImage image = itemManager.getImage(iconID);
		currentTimer = new KittenAttentionTimer(Duration.ofSeconds(-1), image, this, active && config.displayAttentionTimer());
		currentTimer = new KittenAttentionTimer(Duration.ofSeconds(-1), image, this, active && config.displayAttentionTimer());
		currentTimer = new KittenAttentionTimer(Duration.ofSeconds(-1), image, this, active && config.displayAttentionTimer());

		infoBoxManager.addInfoBox(currentTimer);
		infoBoxManager.addInfoBox(currentTimer);
		infoBoxManager.addInfoBox(currentTimer);

		activityTimers.add(currentTimer);
		activityTimers.add(currentTimer);
		activityTimers.add(currentTimer);
	}

	private void removeTimers()
	{
		infoBoxManager.removeInfoBox(currentTimer);

		for (Timer timer : activityTimers)
		{
			if (timer == null)
			{
				continue;
			}
			infoBoxManager.removeInfoBox(timer);
		}

		activityTimers.clear();
		currentTimer = null;
	}

	private void resetTimer(long seconds)
	{
		if (currentTimer == null)
		{
			return;
		}

		if (seconds == -1)
		{
			removeTimers();
			return;
		}

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
			currentTimer.setVisible(active && config.displayAttentionTimer());
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
				currentKitten = currentKitten == null ? new Kitten(client, this, npc.getWorldLocation()) : currentKitten;
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
				case KittenMessage.CHAT_NEW_KITTEN:
				case KittenMessage.CHAT_HUNGRY:
					// TODO: Set up new kitten stuff
					resetTimer(Kitten.HUNGER_TIME);
					break;
				case KittenMessage.CHAT_STROKE:
					// TODO: Check when last interaction was for additional time
					break;
				case KittenMessage.CHAT_ATTENTION:
					resetTimer(Kitten.ATTENTION_TIME_DEFAULT);
					break;
				case KittenMessage.CHAT_REALLY_HUNGRY:
					// TODO: Send notification
					resetTimer(Kitten.HUNGER_WARN_TIME);
					break;
				case KittenMessage.CHAT_GROWN_UP:
					// TODO: Send notification
					resetTimer(-1);
					break;
				default:
					break;
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		switch (event.getMessage())
		{
			case KittenMessage.GAME_FEED:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Kitten Timer: " + KittenMessage.GAME_FEED, null);
				resetTimer(Kitten.HUNGER_TIME);
				break;
			case KittenMessage.GAME_STROKE:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Kitten Timer: " + KittenMessage.GAME_STROKE, null);
				resetTimer(Kitten.ATTENTION_STROKE_TIME);
				break;
			case KittenMessage.GAME_ATTENTION:
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Kitten Timer: " + KittenMessage.GAME_ATTENTION, null);
				// TODO: Send notification
				break;
			case KittenMessage.NPC_EXAMINE:
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
			currentTimer.setVisible(active && config.displayAttentionTimer());
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

	public KittenTimerConfig getConfig()
	{
		return config;
	}
}
