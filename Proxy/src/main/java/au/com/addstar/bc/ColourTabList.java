package au.com.addstar.bc;

import com.google.common.collect.Sets;

import au.com.addstar.bc.sync.PropertyChangeEvent;
import au.com.addstar.bc.sync.SyncManager;
import au.com.addstar.bc.util.ReflectionUtil;

import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.connection.LoginResult;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import net.md_5.bungee.protocol.packet.PlayerListItem.Action;
import net.md_5.bungee.protocol.packet.PlayerListItem.Item;
import net.md_5.bungee.tab.TabList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ColourTabList extends TabList
{
	private static final int PING_THRESHOLD = 20;
	private static ListUpdater mUpdater = new ListUpdater();
	private static Set<ColourTabList> mTabLists = Sets.newConcurrentHashSet();
	
	public static void initialize(Plugin plugin)
	{
		ProxyServer.getInstance().getPluginManager().registerListener(plugin, mUpdater);
	}
	
	private int lastPing;
	private Set<ProxiedPlayer> mVisiblePlayers = Sets.newConcurrentHashSet();
	private String mHeaderContents;
	private String mFooterContents;
	private boolean mHasInited;
	private SkinData mForcedSkinData;
	// ==== 1.7 compat ====
	private String mLastName;

	public ColourTabList(ProxiedPlayer player)
	{
		super(player);
		synchronized(mTabLists)
		{
			mTabLists.add(this);
		}
	}
	
	private static String getName(ProxiedPlayer player)
	{
		PlayerSettings settings = BungeeChat.instance.getManager().getSettings(player);
		return settings.tabColor + ChatColor.stripColor(player.getDisplayName());
	}
	
	private static String getDispName(ProxiedPlayer player)
	{
		return getName(player);
	}
	
	public static boolean isNewTab(ProxiedPlayer player)
	{
		return player.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_8;
	}
	
	public void setOverrideSkin(SkinData skin)
	{
		mForcedSkinData = skin;
		PlayerListItem packet = createPacket(Action.ADD_PLAYER, createItem(player));
		
		for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers())
		{
			if(isVisible(p, player) && isNewTab(p))
				p.unsafe().sendPacket(packet);

		}
	}
	
	@Override
	public void onConnect()
	{
		mLastName = getName(player);
		Debugger.logt("Connect %s with %s", player.getName(), mLastName);
		updateAll();
	}
	
	public void onJoinPeriodComplete()
	{
		Debugger.logt("Join over %s", player.getName());
		mHasInited = true;
		updateAll();
	}
	
	@Override
	public void onPingChange( int ping )
	{
		if ( ping - PING_THRESHOLD > lastPing && ping + PING_THRESHOLD < lastPing )
		{
			lastPing = ping;
			PlayerListItem packet = createPacket(Action.UPDATE_LATENCY, createItem(player));
            
			for(ProxiedPlayer player : ProxyServer.getInstance().getPlayers())
			{
				if(isVisible(player, super.player))
					sendPacket(packet, player);
			}
		}
	}

	@Override
	public void onServerChange() {

	}

	@Override
	public void onDisconnect()
	{
		Debugger.logt("Disconnect %s", player.getName());
		Item item = createItem(player, mLastName);
		
		PlayerListItem packet = createPacket(Action.REMOVE_PLAYER, item);
		
		for(ProxiedPlayer player : ProxyServer.getInstance().getPlayers())
		{
			if(isVisible(player, super.player))
				sendPacket(packet, player);
		}
		
		ProxyServer.getInstance().getScheduler().schedule(BungeeChat.instance, ColourTabList::updateAllHeaders, 50, TimeUnit.MILLISECONDS);
		
		mVisiblePlayers.clear();
		mTabLists.remove(this);
		
		synchronized(mTabLists) {
			for (ColourTabList tablist : mTabLists) {
				tablist.mVisiblePlayers.remove(player);
			}
		}
	}

	private void onUpdateGamemode( PlayerListItem packet )
	{
		ArrayList<Item> items = null;
		for(Item item : packet.getItems())
		{
			ProxiedPlayer other = ProxyServer.getInstance().getPlayer(item.getUuid());
			if (other == null)
				continue;
			
			if (isVisible(player, other))
			{
				if (items == null)
					items = new ArrayList<>(packet.getItems().length);
				items.add(item);
			}
		}
		
		if (items != null)
		{
			final Item[] array = items.toArray(new Item[0]);
			packet.setItems(array);
            player.unsafe().sendPacket(packet);
		}
	}
	
	@Override
	public void onUpdate( PlayerListItem packet )
	{
		// Fake players do not need to be handled pre 1.8
		if (!isNewTab(player))
			return;
		switch(packet.getAction()) {
            case UPDATE_GAMEMODE:
                onUpdateGamemode(packet);
                return;
            case ADD_PLAYER:
                // THis is really just a fudge to remove citizens fake players from the TabList,
                // but it adds them quickly to ensure citizens doesnt get confused.
                ArrayList<Item> items = null;
                for (Item item : packet.getItems()) {
                    // Only fake players will be allowed to pass through. This should allow citizens to work
                    if (ProxyServer.getInstance().getPlayer(item.getUuid()) == null) {
                        if (items == null)
                            items = new ArrayList<>(packet.getItems().length);
                        items.add(item);
                    }
                }

                if (items != null) {
                    final Item[] array = items.toArray(new Item[items.size()]);
                    packet.setItems(array);
                    player.unsafe().sendPacket(packet);
                    // Remove them so they dont really show in tab
                    ProxyServer.getInstance().getScheduler().schedule(BungeeChat.instance, new Runnable() {
                        @Override
                        public void run() {
                            PlayerListItem packetRemove = new PlayerListItem();
                            packetRemove.setAction(Action.REMOVE_PLAYER);
                            packetRemove.setItems(array);
                            player.unsafe().sendPacket(packetRemove);
                        }
                    }, 50, TimeUnit.MILLISECONDS);
                }
                return;
            default:
        }
	}
	
	public void updateTabHeaders()
	{
		if (!isNewTab(player))
			return;
		
		String headerString = BungeeChat.instance.getTabHeaderString(player);
		String footerString = BungeeChat.instance.getTabFooterString(player);
		
		if (!headerString.equals(mHeaderContents) || !footerString.equals(mFooterContents))
		{
            player.setTabHeader(TextComponent.fromLegacyText(headerString), TextComponent.fromLegacyText(footerString));
			mHeaderContents = headerString;
			mFooterContents = footerString;
		}
	}
	
	public void updateList()
	{
		ArrayList<Item> toAdd = new ArrayList<>();
		ArrayList<Item> toRemove = new ArrayList<>();
		
		for (ProxiedPlayer p : ProxyServer.getInstance().getPlayers())
		{
			if(isVisible(player, p))
			{
				if(!mVisiblePlayers.contains(p))
				{
					toAdd.add(createItem(p));
					mVisiblePlayers.add(p);
				}
			}
			else if(mVisiblePlayers.contains(p))
			{
				mVisiblePlayers.remove(p);
				toRemove.add(createItem(p));
			}
		}
		
		if (isNewTab(player))
		{
			if (!toAdd.isEmpty())
			{
				PlayerListItem packetAdd = createPacket(Action.ADD_PLAYER, toAdd.toArray(new Item[0]));
				PlayerListItem packetUpdate = createPacket(Action.UPDATE_DISPLAY_NAME, toAdd.toArray(new Item[0]));
				
				sendPacket(packetAdd, player);
				sendPacket(packetUpdate, player);
			}
			
			if (!toRemove.isEmpty())
			{
				PlayerListItem packetRemove = createPacket(Action.REMOVE_PLAYER, toRemove.toArray(new Item[0]));
				
				sendPacket(packetRemove, player);
			}
		}
		else
		{
			for (Item item : toAdd)
			{
				PlayerListItem packet = createPacket(Action.ADD_PLAYER, item);
				sendPacket(packet, player);
			}
			
			for (Item item : toRemove)
			{
				PlayerListItem packet = createPacket(Action.REMOVE_PLAYER, item);
				sendPacket(packet,player);
			}
		}
		
		updateTabHeaders();
	}
	
	/**
	 * To be visible, the to player must either have TL:seeall set, or be able to see all the groups player is in
	 */
	public static boolean isVisible(ProxiedPlayer to, ProxiedPlayer player)
	{
		if(to == player)
			return true;
        TabList tab  = ReflectionUtil.getTabListHandler(player) ;
            if (tab  instanceof ColourTabList && tab != null) {
                if (!((ColourTabList) ReflectionUtil.getTabListHandler(player)).mHasInited)
                    return false;
            }
		SyncManager manager = BungeeChat.instance.getSyncManager();
		boolean canSeeAll = manager.getPropertyBoolean(to, "TL:seeall", false);
		if(canSeeAll)
			return true;
		Collection<String> names = manager.getPropertyNames(player, "TL:group:");
		if(names.isEmpty())
			return true;
		for(String name : names)
		{
			String group = name.split(":")[2];
			if(manager.getPropertyBoolean(player, "TL:group:" + group, false) && !manager.getPropertyBoolean(to, "TL:see:" + group, false))
				return false;
		}
		
		return true;
	}
	
	public static void updateAll()
	{
		synchronized(mTabLists)
		{
			for(ColourTabList list : mTabLists)
				list.updateList();
		}
	}
	
	public static void updateAllHeaders()
	{
		synchronized(mTabLists)
		{
			for(ColourTabList list : mTabLists)
				list.updateTabHeaders();
		}
	}

	public void onUpdateName()
	{
		Debugger.logt("UpdateName %s from %s to %s", player.getName(), mLastName, getName(player));
		if (mLastName == null)
		{
			Debugger.logt("Update name cancelled %s", player.getName());
			return;
		}
		
		PlayerListItem packetRemove = createPacket(Action.REMOVE_PLAYER, createItem(player, mLastName));
		PlayerListItem packetAdd = createPacket(Action.ADD_PLAYER, createItem(player));
		mLastName = getName(player);
	
		for(ProxiedPlayer p : ProxyServer.getInstance().getPlayers())
		{
			// Can only update for other players if the init period is over, and the other player can see me
			if((mHasInited && isVisible(p, player)) || p == player)
			{
				if (isNewTab(p))
					sendPacket(packetAdd, p);
				else
				{
					sendPacket(packetRemove, p);
					sendPacket(packetAdd, p);
				}
			}
		}
	}
	
	public boolean hasInited()
	{
		return mHasInited;
	}
	
	private void sendPacket(PlayerListItem packet, ProxiedPlayer player)
	{
		Debugger.logTabItem(packet, player);
		player.unsafe().sendPacket(packet);
	}
	
	private static void setProfile(Item item, ProxiedPlayer player) {
        LoginResult profile = ((UserConnection)player).getPendingConnection().getLoginProfile();
        item.setUsername(ChatColor.stripColor(player.getDisplayName()));
        item.setUuid(player.getUniqueId());
            TabList t = ReflectionUtil.getTabListHandler(player);
            if(t instanceof ColourTabList) {
                ColourTabList tab = (ColourTabList) t;
                String[][] properties = new String[profile.getProperties().length][];
                for (int i = 0; i < properties.length; ++i) {
                    LoginResult.Property prop = profile.getProperties()[i];
                    if (prop.getName().equals("textures")) {
                        if (tab.mForcedSkinData != null)
                            prop = new LoginResult.Property("textures", tab.mForcedSkinData.value, tab.mForcedSkinData.signature);
                    }

                    if (prop.getSignature() != null)
                        properties[i] = new String[]{prop.getName(), prop.getValue(), prop.getSignature()};
                    else
                        properties[i] = new String[]{prop.getName(), prop.getValue()};
                }

                item.setProperties(properties);
            }

    }
	
	private static Item createItem(ProxiedPlayer player)
	{
		Item item = new Item();
		setProfile(item, player);
		
		item.setDisplayName(getDispName(player));
		item.setGamemode(0);
		item.setPing(player.getPing());
		
		return item;
	}
	
	private static Item createItem(ProxiedPlayer player, String name)
	{
		Item item = new Item();
		setProfile(item, player);
		
		if (name != null)
			item.setDisplayName(name);
		else
			item.setDisplayName(player.getName());

		item.setGamemode(0);
		item.setPing(player.getPing());
		
		return item;
	}
	
	private static PlayerListItem createPacket(Action action, Item... items)
	{
		PlayerListItem packet = new PlayerListItem();
		packet.setAction(action);
		packet.setItems(items);
		return packet;
	}

	public static class ListUpdater implements Listener
	{
		@EventHandler
		public void onPropertyChange(PropertyChangeEvent event)
		{
			if(!event.getProperty().startsWith("TL:"))
				return;
			
			updateAll();
		}
	}
}
