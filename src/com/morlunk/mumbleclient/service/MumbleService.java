package com.morlunk.mumbleclient.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import android.annotation.TargetApi;
import android.media.AudioManager;
import junit.framework.Assert;
import net.sf.mumble.MumbleProto.Reject;
import net.sf.mumble.MumbleProto.UserRemove;
import net.sf.mumble.MumbleProto.UserState;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.PixelFormat;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.google.protobuf.Message.Builder;
import com.morlunk.mumbleclient.Globals;
import com.morlunk.mumbleclient.R;
import com.morlunk.mumbleclient.Settings;
import com.morlunk.mumbleclient.app.ChannelActivity;
import com.morlunk.mumbleclient.app.ServerList;
import com.morlunk.mumbleclient.app.db.DbAdapter;
import com.morlunk.mumbleclient.app.db.Favourite;
import com.morlunk.mumbleclient.app.db.Server;
import com.morlunk.mumbleclient.service.MumbleProtocol.DisconnectReason;
import com.morlunk.mumbleclient.service.MumbleProtocol.MessageType;
import com.morlunk.mumbleclient.service.audio.AudioInput;
import com.morlunk.mumbleclient.service.audio.AudioOutputHost;
import com.morlunk.mumbleclient.service.model.Channel;
import com.morlunk.mumbleclient.service.model.Message;
import com.morlunk.mumbleclient.service.model.User;

/**
 * Service for providing the client an access to the connection.
 * 
 * MumbleService manages the MumbleClient connection and provides access to it
 * for binding activities.
 * 
 * @author Rantanen
 */
public class MumbleService extends Service implements OnInitListener, Observer {

	public class LocalBinder extends Binder {
		public MumbleService getService() {
			return MumbleService.this;
		}
	}

	public class ServiceAudioOutputHost extends AbstractHost implements
			AudioOutputHost {
		abstract class ServiceProtocolMessage extends ProtocolMessage {
			@Override
			protected Iterable<BaseServiceObserver> getObservers() {
				return observers.values();
			}
		}

		@Override
		public void setTalkState(final User user, final int talkState) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					user.talkingState = talkState;
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserTalkingUpdated(user);
				}
			});
		}

		@Override
		public void setSelfMuted(final User user, final boolean muted) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					user.selfMuted = muted;
					user.selfDeafened = false;
					updateNotificationState(user);

					// Update other clients about mute status
					new Thread(new Runnable() {
						@Override
						public void run() {
							final UserState.Builder us = UserState.newBuilder();
							us.setSession(user.session);
							us.setSelfMute(user.selfMuted);
							us.setSelfDeaf(user.selfDeafened);
							mClient.sendTcpMessage(MessageType.UserState, us);
						}
					}).start();
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserUpdated(user);
				}
			});
		}

		@Override
		public void setSelfDeafened(final User user, final boolean deafened) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					user.selfDeafened = deafened;
					user.selfMuted = deafened;
					updateNotificationState(user);

					// Update other clients about deafened status
					new Thread(new Runnable() {
						@Override
						public void run() {
							final UserState.Builder us = UserState.newBuilder();
							us.setSession(user.session);
							us.setSelfMute(user.selfMuted);
							us.setSelfDeaf(user.selfDeafened);
							mClient.sendTcpMessage(MessageType.UserState, us);
						}
					}).start();
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserUpdated(user);
				}
			});
		}

		@Override
		public boolean isDeafened() {
			if (getCurrentUser() != null)
				return getCurrentUser().selfDeafened;
			else
				return true; // Assume deafened, we don't want audio playing if
								// we're not connected yet.
		}
    }

	public class ServiceConnectionHost extends AbstractHost implements
			MumbleConnectionHost {
		abstract class ServiceProtocolMessage extends ProtocolMessage {
			@Override
			protected Iterable<BaseServiceObserver> getObservers() {
				return observers.values();
			}
		}

		public void setConnectionState(final int state) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					if (MumbleService.this.state == state) {
						return;
					}

					MumbleService.this.state = state;

					updateConnectionState();
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer) {
				}
			});
		}

		@Override
		public void setReject(Reject reject) {
			disconnectReason = DisconnectReason.Reject;
			rejectReason = reject;
		}

		@Override
		public void setGenericError(String error) {
			disconnectReason = DisconnectReason.Generic;
			genericReason = error;
		}

		@Override
		public boolean hasError() {
			return disconnectReason != null;
		}
	}

	/**
	 * Connection host for MumbleConnection.
	 * 
	 * MumbleConnection uses this interface to communicate back to
	 * MumbleService. Since MumbleConnection processes the data packets in a
	 * background thread these methods will be called from that thread.
	 * MumbleService should expose itself as a single threaded Service so its
	 * consumers don't need to bother with synchronizing. For this reason these
	 * handlers should take care of the required synchronization.
	 * 
	 * Also it is worth noting that in case a certain handler doesn't need
	 * synchronizing for its own purposes it might need it to maintain the order
	 * of events. Forwarding the CURRENT_USER_UPDATED event shouldn't be done
	 * before the USER_ADDED event has been processed for that user. For this
	 * reason even events like the CURRENT_USER_UPDATED are posted to the
	 * MumbleService handler.
	 */
	class ServiceProtocolHost extends AbstractHost implements
			MumbleProtocolHost {
		abstract class ServiceProtocolMessage extends ProtocolMessage {
			@Override
			protected Iterable<BaseServiceObserver> getObservers() {
				return observers.values();
			}
		}

		@Override
		public void channelAdded(final Channel channel) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					channels.add(channel);
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onChannelAdded(channel);
				}
			});
		}

		@Override
		public void channelRemoved(final int channelId) {
			handler.post(new ServiceProtocolMessage() {
				Channel channel;

				@Override
				public void process() {
					for (int i = 0; i < channels.size(); i++) {
						if (channels.get(i).id == channelId) {
							channel = channels.remove(i);
							break;
						}
					}
					sortCurrentChannels();
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onChannelRemoved(channel);
				}
			});
		}

		@Override
		public void channelMoved(Channel channel) {
			sortCurrentChannels(); // Sort when moved. We already send a channel
									// update broadcast with channelUdpated.
		}

		@Override
		public void channelUpdated(final Channel channel) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					for (int i = 0; i < channels.size(); i++) {
						if (channels.get(i).id == channel.id) {
							channels.set(i, channel);
							break;
						}
					}
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onChannelUpdated(channel);
				}
			});
		}

		public void currentChannelChanged() {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onCurrentChannelChanged();
				}
			});
		}

		@Override
		public void currentUserUpdated() {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onCurrentUserUpdated();
				}
			});
		}

		public void messageReceived(final Message msg) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					if (settings.isTextToSpeechEnabled() && !isDeafened())
						readMessage(msg);

					String message = chatFormatter.formatMessage(msg);
					postChatMessage(message);
					messages.add(msg);

					if (settings.isChatNotifyEnabled() && !activityVisible) {
						unreadMessages.add(msg);
						showChatNotification();
					}
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onMessageReceived(msg);
				}
			});
		}

		public void messageSent(final Message msg) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					String message = chatFormatter.formatMessage(msg);
					postChatMessage(message);
					messages.add(msg);
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onMessageSent(msg);
				}
			});
		}

		@Override
		public void setSynchronized(final boolean synced) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					MumbleService.this.synced = synced;
					updateConnectionState();
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer) {
				}
			});
		}

		@Override
		public void userAdded(final User user) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					users.add(user);
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserAdded(user);
				}
			});
		}

		@Override
		public void userRemoved(final int userId, final UserRemove remove) {
			handler.post(new ServiceProtocolMessage() {
				private User user;

				@Override
				public void process() {
					for (int i = 0; i < users.size(); i++) {
						if (users.get(i).session == userId) {
							this.user = users.remove(i);
							String disconnectMessage = chatFormatter
									.formatUserStateUpdate(user, null);
							postChatMessage(disconnectMessage);
							return;
						}
					}

					Assert.fail("Non-existant user was removed");
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserRemoved(user, remove);
				}
			});
		}

		@Override
		public void userUpdated(final User user) {
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					for (int i = 0; i < users.size(); i++) {
						if (users.get(i).session == user.session) {
							users.set(i, user);

							return;
						}
					}
					Assert.fail("Non-existant user was updated");
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserUpdated(user);
				}
			});
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * com.morlunk.mumbleclient.service.MumbleProtocolHost#permissionDenied
		 * (net.sf.mumble.MumbleProto.PermissionDenied.DenyType,
		 * net.sf.mumble.MumbleProto.PermissionDenied)
		 */
		@Override
		public void permissionDenied(final String reason, final int denyType) {
			handler.post(new ServiceProtocolMessage() {

				@Override
				public void process() {
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onPermissionDenied(reason, denyType);
				}
			});
		}

		@Override
		public void userStateUpdated(final User user, final UserState state) {
			final String stateString = chatFormatter.formatUserStateUpdate(
					user, state);
			handler.post(new ServiceProtocolMessage() {
				@Override
				public void process() {
					if (isConnected()) {
						postChatMessage(stateString);
					}
				}

				@Override
				protected void broadcast(final BaseServiceObserver observer)
						throws RemoteException {
					observer.onUserStateUpdated(user, state);
				}
			});
		}

		@Override
		public void setReject(Reject reject) {
			disconnectReason = DisconnectReason.Reject;
			rejectReason = reject;
		}

		@Override
		public void setKick(UserRemove kick) {
			disconnectReason = DisconnectReason.Kick;
			kickReason = kick;
		}
	}

	private BroadcastReceiver audioNotificationReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(ACTION_MUTE))
				setMuted(!isMuted());
			else if (intent.getAction().equals(ACTION_DEAFEN))
				setDeafened(!isDeafened());
		};
	};

	private BroadcastReceiver stopReconnectReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d(Globals.LOG_TAG, "Canceled reconnection");
			if (reconnectTimer != null)
				reconnectTimer.cancel();
			hideDisconnectNotification();
		}

	};

    private BroadcastReceiver toggleOverlayReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Toggle overlay
            if(mOverlay == null) {
                mOverlay = new PlumbleOverlay(MumbleService.this);
                mOverlay.show();
            } else {
                mOverlay.hide();
                mOverlay = null;
            }
        }
    };

    /**
     * Listens for AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED
     */
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(isInitialStickyBroadcast())
                return; // Only listen for changes

            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
            Log.d(Globals.LOG_TAG, "SCO enabled: "+state);
            if(state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                mProtocol.setupAudioOutput(true);
                setupAudioInput(); // Reconfigure audio input.
            } else if(state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                if(isConnected()) {
                    mProtocol.setupAudioOutput(false);
                    setupAudioInput();
                }
                unregisterReceiver(this);
            }
        }
    };

	public static final int CONNECTION_STATE_DISCONNECTED = 0;
	public static final int CONNECTION_STATE_CONNECTING = 1;
	public static final int CONNECTION_STATE_SYNCHRONIZING = 2;
	public static final int CONNECTION_STATE_CONNECTED = 3;

	private static final String[] CONNECTION_STATE_NAMES = { "Disconnected",
			"Connecting", "Synchronizing", "Connected" };

	public static final String ACTION_CONNECT = "mumbleclient.action.CONNECT";
	public static final String ACTION_MUTE = "mumbleclient.action.MUTE";
	public static final String ACTION_DEAFEN = "mumbleclient.action.DEAFEN";
	public static final String ACTION_CANCEL_RECONNECT = "mumbleclient.action.CANCEL_RECONNECT";
    public static final String ACTION_TOGGLE_OVERLAY = "mumbleclient.action.TOGGLE_OVERLAY";

	public static final String EXTRA_MESSAGE = "mumbleclient.extra.MESSAGE";
	public static final String EXTRA_CONNECTION_STATE = "mumbleclient.extra.CONNECTION_STATE";
	public static final String EXTRA_SERVER = "mumbleclient.extra.SERVER";

	public static final Integer RECONNECT_TIME = 10000; // 10s

	public static final Integer STATUS_NOTIFICATION_ID = 1;
	public static final Integer DISCONNECT_NOTIFICATION_ID = 2;

	private MumbleConnection mClient;
	private MumbleProtocol mProtocol;

	private DbAdapter dbAdapter;
	private Settings settings;
	private Server connectedServer;

	private Thread mClientThread;
	private AudioInput mAudioInput;

	private NotificationCompat.Builder mStatusNotificationBuilder;
	private NotificationCompat.Builder mDisconnectNotificationBuilder;

	private Timer reconnectTimer; // Timer to reconnect after forceful
									// disconnection

    private PlumbleOverlay mOverlay;
	private View pttOverlayView; // Hot corner overlay view

	private TextToSpeech tts;

	private WakeLock wakeLock;

	private final LocalBinder mBinder = new LocalBinder();
	final Handler handler = new Handler();

	/**
	 * Used to monitor the state of the server list activity, to judge whether
	 * to show chat notifications or not.
	 */
	private boolean activityVisible = true;

	int state;
	boolean synced;
	int serviceState;

	/** @category Disconnect */
	private DisconnectReason disconnectReason;
	/** @category Disconnect */
	private Reject rejectReason;
	/** @category Disconnect */
	private UserRemove kickReason;
	/** @category Disconnect */
	private String genericReason;

	private PlumbleChatFormatter chatFormatter;

	final List<String> chatMessages = new LinkedList<String>();
	final List<String> unreadChatMessages = new LinkedList<String>();
	final List<Message> messages = new LinkedList<Message>();
	final List<Message> unreadMessages = new LinkedList<Message>();
	final List<Channel> channels = new ArrayList<Channel>();
	final List<Channel> sortedChannels = new ArrayList<Channel>();
	final List<User> users = new ArrayList<User>();

	private List<Favourite> favourites;

	// Use concurrent hash map so we can modify the collection while iterating.
	private final Map<Object, BaseServiceObserver> observers = new ConcurrentHashMap<Object, BaseServiceObserver>();

	private ServiceProtocolHost mProtocolHost;
	private ServiceConnectionHost mConnectionHost;
	public ServiceAudioOutputHost mAudioHost;

	public DbAdapter getDatabaseAdapter() {
		return dbAdapter;
	}

	public Server getConnectedServer() {
		return connectedServer;
	}

	/**
	 * @return True if the connected server does not have a database
	 *         representation; hence, 'public'.
	 */
	public boolean isConnectedServerPublic() {
		return connectedServer.getId() == -1;
	}

	public boolean canSpeak() {
		return mProtocol != null && mProtocol.canSpeak;
	}

	public void disconnect() {
		// Call disconnect on the connection.
		// It'll notify us with DISCONNECTED when it's done.
		connectedServer = null;
		if (mClient != null) {
			mClient.disconnect();
		}
	}

	public Channel getChannel(int channelId) {
		for (Channel channel : channels) {
			if (channel.id == channelId)
				return channel;
		}
		return null;
	}

	public Map<Integer, List<User>> getChannelMap() {
		return mProtocol.channelMap;
	}

	public List<Channel> getChannelList() {
		return Collections.unmodifiableList(channels);
	}

	/**
	 * Gets a list of channels sorted alphabetically, by position, and
	 * hierarchy.
	 * 
	 * @return A list of Channel objects in use on the server, sorted.
	 */
	public List<Channel> getSortedChannelList() {
		return Collections.unmodifiableList(sortedChannels);
	}

	private List<Channel> getNestedChannels(Channel channel,
			List<Channel> channels) {
		List<Channel> nestedChannels = new ArrayList<Channel>();
		for (Channel c : channels) {
			if (c.parent == channel.id) {
				nestedChannels.add(c);
				List<Channel> internalChannels = getNestedChannels(c, channels);
				nestedChannels.addAll(internalChannels);
			}
		}

		return nestedChannels;
	}

	/**
	 * Sorts and organizes the channel list into a hierarchy.
	 */
	public void sortCurrentChannels() {
		List<Channel> unsortedChannels = new ArrayList<Channel>(
				getChannelList());
		List<Channel> sortedChannels = new ArrayList<Channel>();

		sortChannelList(unsortedChannels);

		for (Channel channel : unsortedChannels) {
			if (channel.parent == -1) {
				sortedChannels.add(channel);
				sortedChannels.addAll(getNestedChannels(channel,
						unsortedChannels));
			}
		}

		this.sortedChannels.clear();
		this.sortedChannels.addAll(sortedChannels);
	}

	/**
	 * Sorts the passed list of channels alphebetically and by position.
	 * 
	 * @param channels
	 */
	private void sortChannelList(List<Channel> channels) {
		Collections.sort(channels, new Comparator<Channel>() {
			@Override
			public int compare(Channel lhs, Channel rhs) {
				return lhs.name.toLowerCase(Locale.getDefault()).compareTo(
						rhs.name.toLowerCase(Locale.getDefault()));
			}
		});
		Collections.sort(channels, new Comparator<Channel>() {
			@Override
			public int compare(Channel lhs, Channel rhs) {
				return ((Integer) lhs.position)
						.compareTo(((Integer) rhs.position));
			}
		});
	}

	public void updateFavourites() {
		DbAdapter dbAdapter = getDatabaseAdapter();
		favourites = dbAdapter.fetchAllFavourites(getConnectedServer().getId());
	}

	public List<Favourite> getFavourites() {
		return favourites;
	}

	public Favourite getFavouriteForChannel(Channel channel) {
		for (Favourite favourite : favourites) {
			if (favourite.getChannelId() == channel.id)
				return favourite;
		}
		return null;
	}

	public int getCodec() {
		if (!isConnected())
			return -1;
		if (mProtocol.codec == MumbleProtocol.CODEC_NOCODEC) {
			throw new IllegalStateException(
					"Called getCodec on a connection with unsupported codec");
		}

		return mProtocol.codec;
	}

	public int getConnectionState() {
		return serviceState;
	}

	public Channel getCurrentChannel() {
		if (!isConnected())
			return null;
		return mProtocol.currentChannel;
	}

	public User getCurrentUser() {
		if (!isConnected() || mProtocol == null)
			return null;
		return mProtocol.currentUser;
	}

	public User getUser(int session) {
		for (User user : users) {
			if (user.session == session)
				return user;
		}
		return null;
	}

	/**
	 * Returns the reason that the user was disconnected. More data can be
	 * obtained from the disconnection-specific methods.
	 * 
	 * @category Disconnect
	 * @return The reason the user was disconnected
	 */

	public DisconnectReason getDisconnectReason() {
		return disconnectReason;
	}

	/**
	 * @category Disconnect
	 */
	public Reject getRejectReason() {
		Assert.assertEquals(disconnectReason, DisconnectReason.Reject);
		return rejectReason;
	}

	/**
	 * @category Disconnect
	 */
	public UserRemove getKickReason() {
		Assert.assertEquals(disconnectReason, DisconnectReason.Kick);
		return kickReason;
	}

	/**
	 * @category Disconnect
	 */
	public String getGenericDisconnectReason() {
		Assert.assertEquals(disconnectReason, DisconnectReason.Generic);
		return genericReason;
	}

	public boolean isActivityVisible() {
		return activityVisible;
	}

	public void setActivityVisible(boolean activityVisible) {
		this.activityVisible = activityVisible;
	}

	public List<Message> getMessageList() {
		return Collections.unmodifiableList(messages);
	}

	public List<User> getUserList() {
		return Collections.unmodifiableList(users);
	}

	public boolean isConnected() {
		return serviceState == CONNECTION_STATE_CONNECTED;
	}

	public boolean isRecording() {
		return mAudioInput.isRecording();
	}

	public boolean isDeafened() {
		return mProtocol.currentUser.selfDeafened;
	}

	public boolean isMuted() {
		return mProtocol.currentUser.selfMuted;
	}

	public void registerSelf() {
		mProtocol.registerUser(getCurrentUser());
	}

	public void joinChannel(final int channelId) {
		mProtocol.joinChannel(channelId);
	}

	public void sendAccessTokens(List<String> tokens) {
		mProtocol.sendAccessTokens(tokens);
	}

	/**
	 * Retrieves and sends the access tokens for the active server from the
	 * database.
	 */
	public void sendAccessTokens() {
		DbAdapter dbAdapter = getDatabaseAdapter();
		AsyncTask<DbAdapter, Void, Void> accessTask = new AsyncTask<DbAdapter, Void, Void>() {

			@Override
			protected Void doInBackground(DbAdapter... params) {
				DbAdapter adapter = params[0];
				List<String> tokens = adapter
						.fetchAllTokens(getConnectedServer().getId());
				sendAccessTokens(tokens);
				return null;
			}
		};
		accessTask.execute(dbAdapter);
	}

	@Override
	public IBinder onBind(final Intent intent) {
		Log.i(Globals.LOG_TAG, "MumbleService: Bound");
		return mBinder;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		// Make sure our notification is gone.
		hideNotification();
		hideDisconnectNotification();

		settings = Settings.getInstance(this);

		PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
				"plumbleLock");

		Log.i(Globals.LOG_TAG, "MumbleService: Created");
		serviceState = CONNECTION_STATE_DISCONNECTED;

		chatFormatter = new PlumbleChatFormatter(this);

		dbAdapter = new DbAdapter(this);
		dbAdapter.open();

		registerReceiver(audioNotificationReceiver, new IntentFilter(
				ACTION_MUTE));
		registerReceiver(audioNotificationReceiver, new IntentFilter(
				ACTION_DEAFEN));
		registerReceiver(stopReconnectReceiver, new IntentFilter(
				ACTION_CANCEL_RECONNECT));
        registerReceiver(toggleOverlayReceiver, new IntentFilter(
                ACTION_TOGGLE_OVERLAY));
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		// Make sure our notification is gone.
		hideNotification();

        unregisterReceiver(audioNotificationReceiver);
        unregisterReceiver(stopReconnectReceiver);
		unregisterReceiver(toggleOverlayReceiver);

		Log.i(Globals.LOG_TAG, "MumbleService: Destroyed");

		dbAdapter.close();
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags,
			final int startId) {
		Log.i(Globals.LOG_TAG, "MumbleService: Starting service");

		if (intent.hasExtra(EXTRA_SERVER)) {
			Server server = intent
					.getParcelableExtra(MumbleService.EXTRA_SERVER);
			connectToServer(server);
		}

		return START_NOT_STICKY;
	}

	/**
	 * Connects to the passed 'Server' object. Disconnects from existing server
	 * first.
	 */
	public void connectToServer(Server server) {
		disconnectReason = null;
		try {
			Thread disconnectThread = new Thread(new Runnable() {
				@Override
				public void run() {
					disconnect();
				}
			});
			disconnectThread.start();
			disconnectThread.join();
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}

		this.connectedServer = server;

		if (reconnectTimer != null)
			reconnectTimer.cancel(); // Cancel auto-reconnect timer

		hideDisconnectNotification();

		String plumbleVersion;
		try {
			plumbleVersion = getPackageManager().getPackageInfo(
					getPackageName(), 0).versionName;
		} catch (NameNotFoundException e) {
			plumbleVersion = "???";
		}
		final String certificatePath = settings.getCertificatePath();
		final String certificatePassword = settings.getCertificatePassword();

		mProtocolHost = new ServiceProtocolHost();
		mConnectionHost = new ServiceConnectionHost();
		mAudioHost = new ServiceAudioOutputHost();

		mClient = new MumbleConnection(mConnectionHost, plumbleVersion,
				connectedServer.getHost(), connectedServer.getPort(),
				connectedServer.getUsername(), connectedServer.getPassword(),
				certificatePath, certificatePassword, settings.isTcpForced(),
				settings.isOpusDisabled());

		mProtocol = new MumbleProtocol(mProtocolHost, mAudioHost, mClient,
				getApplicationContext());

		mClientThread = mClient.start(mProtocol);

		// Acquire wake lock
		wakeLock.acquire();

		// Enable TTS
		tts = new TextToSpeech(this, this);
	}

    private void setupAudioInput() {
        // Disable old thread, if exists
        if(mAudioInput != null) {
            try {
                mAudioInput.stopRecordingAndBlock();
                mAudioInput.shutdown();
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
        // Initialize audio input
        mAudioInput = new AudioInput(this, mProtocol.codec);
        if (settings.isVoiceActivity())
            mAudioInput.startRecording(); // Immediately begin record if using voice activity
    }

	private void onConnected() {
		settings.addObserver(this);
		serviceState = synced ? CONNECTION_STATE_CONNECTED
				: CONNECTION_STATE_SYNCHRONIZING;

		if (synced) {
			sendAccessTokens();
            setupAudioInput();
			if (settings.isDeafened()) {
				setDeafened(true);
			} else if (settings.isMuted()) {
				setMuted(true);
			}

			updateFavourites();
			showNotification();
			sortCurrentChannels();
		}

		// Create PTT overlay
		if (settings.isPushToTalk()
				&& !settings.getHotCorner().equals(
						Settings.ARRAY_HOT_CORNER_NONE)) {
			createPTTOverlay();
		}
	}

	private void onConnecting() {

	}

	private void onDisconnected() {
		// First disable all hosts to prevent old callbacks from being
		// processed.
		if (mProtocolHost != null) {
			mProtocolHost.disable();
			mProtocolHost = null;
		}

		if (mConnectionHost != null) {
			mConnectionHost.disable();
			mConnectionHost = null;
		}

		if (mAudioHost != null) {
			mAudioHost.disable();
			mAudioHost = null;
		}

		if (mAudioInput != null) {
			try {
				mAudioInput.stopRecordingAndBlock();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				mAudioInput.shutdown();
				mAudioInput = null;
			}
		}

        // Disable bluetooth, if active
        disableBluetooth();

		// Stop threads.
		if (mProtocol != null) {
			mProtocol.stop();
			mProtocol = null;
		}

		if (mClient != null && mClientThread != null) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					mClient.disconnect();
				}
			}).start();

			try {
				mClientThread.join();
			} catch (final InterruptedException e) {
				mClientThread.interrupt();
			}

			// Leave mClient reference intact as its state might still be
			// queried.
			mClientThread = null;
		}

        // Hide overlay
        if(mOverlay != null) {
            mOverlay.hide();
            mOverlay = null;
        }

		// Remove PTT overlay and notification.
		dismissPTTOverlay();
		hideNotification();

		// Now observers shouldn't need these anymore.
		chatMessages.clear();
		unreadChatMessages.clear();
		users.clear();
		messages.clear();
		channels.clear();

		// Stop wakelock
		if (wakeLock.isHeld()) {
			wakeLock.release();
		}

		if (tts != null) {
			tts.stop();
			tts.shutdown();
		}

		settings.deleteObserver(this);

		// If there was a forceful disconnect, show a disconnection notification
		if (disconnectReason != null)
			showDisconnectNotification();
	}

	public void registerObserver(final BaseServiceObserver observer) {
		observers.put(observer, observer);
	}

	public void sendChannelTextMessage(final String message,
			final Channel channel) {
		mProtocol.sendChannelTextMessage(message, channel);
	}

	public void sendUserTextMessage(String string, User chatTarget) {
		mProtocol.sendUserTestMessage(string, chatTarget);
	}

	public List<String> getChatMessages() {
		return Collections.unmodifiableList(chatMessages);
	}

	public List<String> getUnreadChatMessages() {
		return Collections.unmodifiableList(unreadChatMessages);
	}

	public void clearUnreadChatMessages() {
		unreadChatMessages.clear();
	}

	public void sendUdpMessage(final byte[] buffer, final int length) {
		mClient.sendUdpMessage(buffer, length, false);
	}

	public void sendTcpMessage(MessageType t, Builder b) {
		mClient.sendTcpMessage(t, b);
	}

	/**
	 * @return the mAudioHost
	 */
	public ServiceAudioOutputHost getAudioHost() {
		return mAudioHost;
	}

	public void setPushToTalk(final boolean state) {
		if (mProtocol == null || mProtocol.currentUser == null)
			return;

		Assert.assertTrue("Push to talk not on, but setPushToTalk called!",
				!settings.isVoiceActivity());

		if (state) {
			mAudioHost.setTalkState(getCurrentUser(),
					AudioOutputHost.STATE_TALKING);
			mAudioInput.startRecording();
		} else {
			mAudioHost.setTalkState(getCurrentUser(),
					AudioOutputHost.STATE_PASSIVE);
			mAudioInput.stopRecording();
		}
	}

	public void setMuted(final boolean state) {
		if (mAudioHost != null && mProtocol != null
				&& mProtocol.currentUser != null) {
			mAudioHost.setSelfMuted(mProtocol.currentUser, state);
			settings.setMutedAndDeafened(state, false);
		}
	}

	public void setDeafened(final boolean state) {
		if (mAudioHost != null && mProtocol != null
				&& mProtocol.currentUser != null) {
			mAudioHost.setSelfDeafened(mProtocol.currentUser, state);
			settings.setMutedAndDeafened(state, state);
		}
	}

    @TargetApi(8)
    public void enableBluetooth() {
        registerReceiver(bluetoothReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        Log.d(Globals.LOG_TAG, "Starting bluetooth SCO");
        AudioManager audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();
    }

    @TargetApi(8)
    public void disableBluetooth() {
        Log.d(Globals.LOG_TAG, "Ending bluetooth SCO");
        AudioManager audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        audioManager.setBluetoothScoOn(false);
        audioManager.stopBluetoothSco();
    }

	public void updateNotificationState(User user) {
		boolean muted = user.selfMuted;
		boolean deafened = user.selfDeafened;

		String status = null;
		if (muted && !deafened) {
			status = getString(R.string.status_notify_muted);
		} else if (deafened && muted) {
			status = getString(R.string.status_notify_muted_and_deafened);
		}

		mStatusNotificationBuilder.setContentInfo(status);
		mStatusNotificationBuilder.setSmallIcon(R.drawable.ic_stat_notify);

		Notification mStatusNotification = mStatusNotificationBuilder.build();
		NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		manager.notify(STATUS_NOTIFICATION_ID, mStatusNotification);
	}

	public void unregisterObserver(final BaseServiceObserver observer) {
		observers.remove(observer);
	}

	private void broadcastState() {
		for (final BaseServiceObserver observer : observers.values()) {
			try {
				observer.onConnectionStateChanged(serviceState);
			} catch (final RemoteException e) {
				Log.e(Globals.LOG_TAG, "Failed to update connection state", e);
			}
		}

		Log.i(Globals.LOG_TAG, "MumbleService: Connection state changed to "
				+ CONNECTION_STATE_NAMES[serviceState]);
	}

	void hideNotification() {
		// Clear notifications
		stopForeground(true);
	}

	void showNotification() {
		NotificationCompat.Builder builder = new NotificationCompat.Builder(
				this);
		builder.setSmallIcon(R.drawable.ic_stat_notify);
		builder.setTicker(getResources().getString(R.string.plumbleConnected));
		builder.setContentTitle(getResources().getString(R.string.app_name));
		builder.setContentText(getResources().getString(R.string.connected));
		builder.setPriority(NotificationCompat.PRIORITY_HIGH);
		builder.setOngoing(true);

		// Add notification triggers
		Intent muteIntent = new Intent(ACTION_MUTE);
		Intent deafenIntent = new Intent(ACTION_DEAFEN);
        Intent overlayIntent = new Intent(ACTION_TOGGLE_OVERLAY);

		builder.addAction(R.drawable.ic_action_microphone,
				getString(R.string.mute), PendingIntent.getBroadcast(this, 1,
						muteIntent, PendingIntent.FLAG_CANCEL_CURRENT));
		builder.addAction(R.drawable.ic_action_headphones,
				getString(R.string.deafen), PendingIntent.getBroadcast(this, 1,
						deafenIntent, PendingIntent.FLAG_CANCEL_CURRENT));
        builder.addAction(R.drawable.ic_action_channels,
                getString(R.string.overlay), PendingIntent.getBroadcast(this, 2,
                        overlayIntent, PendingIntent.FLAG_CANCEL_CURRENT));

		Intent channelListIntent = new Intent(MumbleService.this,
				ChannelActivity.class);

		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
				channelListIntent, 0);

		builder.setContentIntent(pendingIntent);

		mStatusNotificationBuilder = builder;
		Notification mStatusNotification = mStatusNotificationBuilder.build();

		startForeground(STATUS_NOTIFICATION_ID, mStatusNotification);
	}

	/**
	 * Shows a notification indicating that there was an error and the service
	 * will restart.
	 */
	public void showDisconnectNotification() {
		NotificationCompat.Builder builder = new NotificationCompat.Builder(
				this);
		builder.setSmallIcon(R.drawable.ic_stat_notify);
		String errorTitle = null;
		String errorTicker = null;

		switch (disconnectReason) {
		case Generic:
			errorTitle = errorTicker = getGenericDisconnectReason();
			builder.setContentText(getString(R.string.tapToReconnect));
			if(settings.isAutoReconnectEnabled()) {
				errorTicker = errorTitle + "\n"
						+ getString(R.string.reconnecting, RECONNECT_TIME / 1000);
				builder.setContentText(getString(R.string.reconnecting,
						RECONNECT_TIME / 1000));
				reconnectTimer = new Timer();
				final Server server = connectedServer;
				reconnectTimer.schedule(new TimerTask() {
					@Override
					public void run() {
						connectToServer(server);
					}
				}, RECONNECT_TIME);
				Intent cancelDisconnect = new Intent(ACTION_CANCEL_RECONNECT);
				PendingIntent intent = PendingIntent.getBroadcast(
						getApplicationContext(), 3, cancelDisconnect,
						PendingIntent.FLAG_CANCEL_CURRENT);
				builder.addAction(R.drawable.ic_action_delete_dark,
						getString(android.R.string.cancel), intent);
				
			}
			break;

		case Kick:
			errorTitle = errorTicker = getString(R.string.kickedMessage,
					kickReason.getReason());
			builder.setContentText(getString(R.string.tapToReconnect));
			break;

		case Reject:
			errorTitle = errorTicker = rejectReason.getReason();
			builder.setContentText(getString(R.string.tapToReconnect));
			break;
		}

		builder.setContentTitle(errorTitle);
		builder.setTicker(errorTicker);

		Intent listIntent = new Intent(getApplicationContext(),
				ServerList.class);
		PendingIntent intent = PendingIntent.getActivity(
				getApplicationContext(), 2, listIntent,
				PendingIntent.FLAG_CANCEL_CURRENT);
		builder.setContentIntent(intent);

		this.mDisconnectNotificationBuilder = builder;

		NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		notificationManager.notify(DISCONNECT_NOTIFICATION_ID, builder.build());
	}

	public void hideDisconnectNotification() {
		// Clear notification
		NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		notificationManager.cancel(DISCONNECT_NOTIFICATION_ID);
	}

	public void showChatNotification() {
		if (unreadMessages.size() == 0)
			return;

		Message lastMessage = unreadMessages.get(unreadMessages.size() - 1);

		mStatusNotificationBuilder
				.setTicker(((lastMessage.actor != null && lastMessage.actor.name != null) ? lastMessage.actor.name
						: getString(R.string.server))
						+ ": " + Html.fromHtml(lastMessage.message).toString());

		NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

		for (Message message : unreadMessages) {
			inboxStyle
					.addLine(((message.actor != null && message.actor.name != null) ? message.actor.name
							: getString(R.string.server))
							+ ": " + Html.fromHtml(message.message).toString()); // Escapes
																					// HTML
		}

		mStatusNotificationBuilder.setStyle(inboxStyle);

		Notification notificationCompat = mStatusNotificationBuilder.build();
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		notificationManager.notify(STATUS_NOTIFICATION_ID, notificationCompat);
	}

	public void clearChatNotification() {
		if (unreadMessages == null || mStatusNotificationBuilder == null)
			return;

		unreadMessages.clear();
		mStatusNotificationBuilder.setTicker(null);
		mStatusNotificationBuilder.setStyle(null);

		Notification notificationCompat = mStatusNotificationBuilder.build();
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		notificationManager.notify(STATUS_NOTIFICATION_ID, notificationCompat);
	}

	public void postChatMessage(String message) {
		if (message == null)
			return;

		chatMessages.add(message);
		unreadChatMessages.add(message);
	}

	/**
	 * TTS
	 */
	@Override
	public void onInit(int status) {
		if (status == TextToSpeech.SUCCESS) {
			Log.i(Globals.LOG_TAG, "TTS enabled.");
			int langResult = tts.setLanguage(Locale.getDefault());
			if (langResult == TextToSpeech.LANG_MISSING_DATA
					|| langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
				Log.i(Globals.LOG_TAG, "TTS not available for this locale.");
			}
		} else {
			Log.i(Globals.LOG_TAG, "TTS failed to initialize.");
		}
	}

	public void readMessage(Message msg) {
		StringBuilder ttsText = new StringBuilder();

		if (msg.channelIds > 0) {
			ttsText.append(getString(R.string.channel) + " ");
		}

		if (msg.actor != null)
			ttsText.append(msg.actor.name);
		else
			ttsText.append(getString(R.string.server));

		ttsText.append(": ");
		ttsText.append(Html.fromHtml(msg.message).toString());

		tts.speak(ttsText.toString(), TextToSpeech.QUEUE_ADD, null);
	}

	/**
	 * Creates a system overlay that allows the user to touch the corner of the
	 * screen to push to talk.
	 */
	public void createPTTOverlay() {
		WindowManager.LayoutParams pttParams = new WindowManager.LayoutParams(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
						| WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
						| WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
				PixelFormat.TRANSLUCENT);
		String hotCorner = settings.getHotCorner();
		if (hotCorner.equals(Settings.ARRAY_HOT_CORNER_TOP_LEFT)) {
			pttParams.gravity = Gravity.LEFT | Gravity.TOP;
		} else if (hotCorner.equals(Settings.ARRAY_HOT_CORNER_BOTTOM_LEFT)) {
			pttParams.gravity = Gravity.LEFT | Gravity.BOTTOM;
		} else if (hotCorner.equals(Settings.ARRAY_HOT_CORNER_TOP_RIGHT)) {
			pttParams.gravity = Gravity.RIGHT | Gravity.TOP;
		} else if (hotCorner.equals(Settings.ARRAY_HOT_CORNER_BOTTOM_RIGHT)) {
			pttParams.gravity = Gravity.RIGHT | Gravity.BOTTOM;
		}
		WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
		LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
		pttOverlayView = inflater.inflate(R.layout.ptt_overlay, null);
		final Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
		pttOverlayView.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    setPushToTalk(true);
                    // Vibrate to provide haptic feedback
                    vibrator.vibrate(10);
                    pttOverlayView.setBackgroundColor(0xAA33b5e5);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    setPushToTalk(false);
                    pttOverlayView.setBackgroundColor(0);
                }
                return false;
            }
        });

		// Add layout to window manager
		wm.addView(pttOverlayView, pttParams);
	}

	/**
	 * Removes the system overlay for PTT.
	 */
	public void dismissPTTOverlay() {
		WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
		if (pttOverlayView != null) {
			try {
				wm.removeView(pttOverlayView);
			} catch (Exception e) {
				// We do a catchall here because removing a view from a
				// WindowManager can be unreliable.
				// http://stackoverflow.com/questions/6515004/keeping-track-of-view-added-to-windowmanager-no-findviewbyid-function
			}
		}
	}

	void updateConnectionState() {
		final int oldState = serviceState;

		switch (state) {
		case MumbleConnectionHost.STATE_CONNECTING:
			serviceState = MumbleService.CONNECTION_STATE_CONNECTING;
			onConnecting();
			break;
		case MumbleConnectionHost.STATE_CONNECTED:
			serviceState = synced ? CONNECTION_STATE_CONNECTED
					: CONNECTION_STATE_SYNCHRONIZING;
			onConnected();
			break;
		case MumbleConnectionHost.STATE_DISCONNECTED:
			serviceState = CONNECTION_STATE_DISCONNECTED;
			onDisconnected();
			break;
		default:
			Assert.fail();
		}

		if (oldState != serviceState) {
			broadcastState();
		}
	}

	public void clearChat() {
		chatMessages.clear();
		unreadChatMessages.clear();
		messages.clear();
		unreadMessages.clear();
	}

	// Settings observer
	@Override
	public void update(Observable observable, Object data) {
		Settings settings = (Settings) observable;

		if (data.equals(Settings.OBSERVER_KEY_ALL)) {
			// Create PTT overlay
			dismissPTTOverlay();
			if (settings.isPushToTalk()
					&& !settings.getHotCorner().equals(
							Settings.ARRAY_HOT_CORNER_NONE)) {
				createPTTOverlay();
			}

			// Handle voice activity
			if (settings.isVoiceActivity())
				mAudioInput.startRecording();
			else
				setPushToTalk(false);
		}
	}
}
