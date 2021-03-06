/*
 * Sone - Core.java - Copyright © 2010–2012 David Roden
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.pterodactylus.sone.core;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.pterodactylus.sone.core.Options.DefaultOption;
import net.pterodactylus.sone.core.Options.Option;
import net.pterodactylus.sone.core.Options.OptionWatcher;
import net.pterodactylus.sone.data.Album;
import net.pterodactylus.sone.data.Client;
import net.pterodactylus.sone.data.Image;
import net.pterodactylus.sone.data.Post;
import net.pterodactylus.sone.data.PostReply;
import net.pterodactylus.sone.data.Profile;
import net.pterodactylus.sone.data.Profile.Field;
import net.pterodactylus.sone.data.Reply;
import net.pterodactylus.sone.data.Sone;
import net.pterodactylus.sone.data.Sone.ShowCustomAvatars;
import net.pterodactylus.sone.data.Sone.SoneStatus;
import net.pterodactylus.sone.data.TemporaryImage;
import net.pterodactylus.sone.data.impl.PostImpl;
import net.pterodactylus.sone.fcp.FcpInterface;
import net.pterodactylus.sone.fcp.FcpInterface.FullAccessRequired;
import net.pterodactylus.sone.freenet.wot.Identity;
import net.pterodactylus.sone.freenet.wot.IdentityListener;
import net.pterodactylus.sone.freenet.wot.IdentityManager;
import net.pterodactylus.sone.freenet.wot.OwnIdentity;
import net.pterodactylus.sone.main.SonePlugin;
import net.pterodactylus.util.config.Configuration;
import net.pterodactylus.util.config.ConfigurationException;
import net.pterodactylus.util.logging.Logging;
import net.pterodactylus.util.number.Numbers;
import net.pterodactylus.util.service.AbstractService;
import net.pterodactylus.util.thread.Ticker;
import net.pterodactylus.util.validation.EqualityValidator;
import net.pterodactylus.util.validation.IntegerRangeValidator;
import net.pterodactylus.util.validation.OrValidator;
import net.pterodactylus.util.validation.Validation;
import net.pterodactylus.util.version.Version;
import freenet.keys.FreenetURI;

/**
 * The Sone core.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class Core extends AbstractService implements IdentityListener, UpdateListener, SoneProvider, PostProvider, SoneInsertListener, ImageInsertListener {

	/** The logger. */
	private static final Logger logger = Logging.getLogger(Core.class);

	/** The start time. */
	private final long startupTime = System.currentTimeMillis();

	/** The options. */
	private final Options options = new Options();

	/** The preferences. */
	private final Preferences preferences = new Preferences(options);

	/** The core listener manager. */
	private final CoreListenerManager coreListenerManager = new CoreListenerManager(this);

	/** The configuration. */
	private Configuration configuration;

	/** Whether we’re currently saving the configuration. */
	private boolean storingConfiguration = false;

	/** The identity manager. */
	private final IdentityManager identityManager;

	/** Interface to freenet. */
	private final FreenetInterface freenetInterface;

	/** The Sone downloader. */
	private final SoneDownloader soneDownloader;

	/** The image inserter. */
	private final ImageInserter imageInserter;

	/** Sone downloader thread-pool. */
	private final ExecutorService soneDownloaders = Executors.newFixedThreadPool(10);

	/** The update checker. */
	private final UpdateChecker updateChecker;

	/** The trust updater. */
	private final WebOfTrustUpdater webOfTrustUpdater;

	/** The FCP interface. */
	private volatile FcpInterface fcpInterface;

	/** The times Sones were followed. */
	private final Map<Sone, Long> soneFollowingTimes = new HashMap<Sone, Long>();

	/** Locked local Sones. */
	/* synchronize on itself. */
	private final Set<Sone> lockedSones = new HashSet<Sone>();

	/** Sone inserters. */
	/* synchronize access on this on localSones. */
	private final Map<Sone, SoneInserter> soneInserters = new HashMap<Sone, SoneInserter>();

	/** Sone rescuers. */
	/* synchronize access on this on localSones. */
	private final Map<Sone, SoneRescuer> soneRescuers = new HashMap<Sone, SoneRescuer>();

	/** All local Sones. */
	/* synchronize access on this on itself. */
	private final Map<String, Sone> localSones = new HashMap<String, Sone>();

	/** All remote Sones. */
	/* synchronize access on this on itself. */
	private final Map<String, Sone> remoteSones = new HashMap<String, Sone>();

	/** All known Sones. */
	private final Set<String> knownSones = new HashSet<String>();

	/** All posts. */
	private final Map<String, Post> posts = new HashMap<String, Post>();

	/** All known posts. */
	private final Set<String> knownPosts = new HashSet<String>();

	/** All replies. */
	private final Map<String, PostReply> replies = new HashMap<String, PostReply>();

	/** All known replies. */
	private final Set<String> knownReplies = new HashSet<String>();

	/** All bookmarked posts. */
	/* synchronize access on itself. */
	private final Set<String> bookmarkedPosts = new HashSet<String>();

	/** Trusted identities, sorted by own identities. */
	private final Map<OwnIdentity, Set<Identity>> trustedIdentities = Collections.synchronizedMap(new HashMap<OwnIdentity, Set<Identity>>());

	/** All known albums. */
	private final Map<String, Album> albums = new HashMap<String, Album>();

	/** All known images. */
	private final Map<String, Image> images = new HashMap<String, Image>();

	/** All temporary images. */
	private final Map<String, TemporaryImage> temporaryImages = new HashMap<String, TemporaryImage>();

	/** Ticker for threads that mark own elements as known. */
	private final Ticker localElementTicker = new Ticker();

	/** The time the configuration was last touched. */
	private volatile long lastConfigurationUpdate;

	/**
	 * Creates a new core.
	 *
	 * @param configuration
	 *            The configuration of the core
	 * @param freenetInterface
	 *            The freenet interface
	 * @param identityManager
	 *            The identity manager
	 * @param webOfTrustUpdater
	 *            The WebOfTrust updater
	 */
	public Core(Configuration configuration, FreenetInterface freenetInterface, IdentityManager identityManager, WebOfTrustUpdater webOfTrustUpdater) {
		super("Sone Core");
		this.configuration = configuration;
		this.freenetInterface = freenetInterface;
		this.identityManager = identityManager;
		this.soneDownloader = new SoneDownloader(this, freenetInterface);
		this.imageInserter = new ImageInserter(this, freenetInterface);
		this.updateChecker = new UpdateChecker(freenetInterface);
		this.webOfTrustUpdater = webOfTrustUpdater;
	}

	//
	// LISTENER MANAGEMENT
	//

	/**
	 * Adds a new core listener.
	 *
	 * @param coreListener
	 *            The listener to add
	 */
	public void addCoreListener(CoreListener coreListener) {
		coreListenerManager.addListener(coreListener);
	}

	/**
	 * Removes a core listener.
	 *
	 * @param coreListener
	 *            The listener to remove
	 */
	public void removeCoreListener(CoreListener coreListener) {
		coreListenerManager.removeListener(coreListener);
	}

	//
	// ACCESSORS
	//

	/**
	 * Returns the time Sone was started.
	 *
	 * @return The startup time (in milliseconds since Jan 1, 1970 UTC)
	 */
	public long getStartupTime() {
		return startupTime;
	}

	/**
	 * Sets the configuration to use. This will automatically save the current
	 * configuration to the given configuration.
	 *
	 * @param configuration
	 *            The new configuration to use
	 */
	public void setConfiguration(Configuration configuration) {
		this.configuration = configuration;
		touchConfiguration();
	}

	/**
	 * Returns the options used by the core.
	 *
	 * @return The options of the core
	 */
	public Preferences getPreferences() {
		return preferences;
	}

	/**
	 * Returns the identity manager used by the core.
	 *
	 * @return The identity manager
	 */
	public IdentityManager getIdentityManager() {
		return identityManager;
	}

	/**
	 * Returns the update checker.
	 *
	 * @return The update checker
	 */
	public UpdateChecker getUpdateChecker() {
		return updateChecker;
	}

	/**
	 * Sets the FCP interface to use.
	 *
	 * @param fcpInterface
	 *            The FCP interface to use
	 */
	public void setFcpInterface(FcpInterface fcpInterface) {
		this.fcpInterface = fcpInterface;
	}

	/**
	 * Returns the Sone rescuer for the given local Sone.
	 *
	 * @param sone
	 *            The local Sone to get the rescuer for
	 * @return The Sone rescuer for the given Sone
	 */
	public SoneRescuer getSoneRescuer(Sone sone) {
		Validation.begin().isNotNull("Sone", sone).check().is("Local Sone", isLocalSone(sone)).check();
		synchronized (localSones) {
			SoneRescuer soneRescuer = soneRescuers.get(sone);
			if (soneRescuer == null) {
				soneRescuer = new SoneRescuer(this, soneDownloader, sone);
				soneRescuers.put(sone, soneRescuer);
				soneRescuer.start();
			}
			return soneRescuer;
		}
	}

	/**
	 * Returns whether the given Sone is currently locked.
	 *
	 * @param sone
	 *            The sone to check
	 * @return {@code true} if the Sone is locked, {@code false} if it is not
	 */
	public boolean isLocked(Sone sone) {
		synchronized (lockedSones) {
			return lockedSones.contains(sone);
		}
	}

	/**
	 * Returns all Sones, remote and local.
	 *
	 * @return All Sones
	 */
	public Set<Sone> getSones() {
		Set<Sone> allSones = new HashSet<Sone>();
		allSones.addAll(getLocalSones());
		allSones.addAll(getRemoteSones());
		return allSones;
	}

	/**
	 * Returns the Sone with the given ID, regardless whether it’s local or
	 * remote.
	 *
	 * @param id
	 *            The ID of the Sone to get
	 * @return The Sone with the given ID, or {@code null} if there is no such
	 *         Sone
	 */
	public Sone getSone(String id) {
		return getSone(id, true);
	}

	/**
	 * Returns the Sone with the given ID, regardless whether it’s local or
	 * remote.
	 *
	 * @param id
	 *            The ID of the Sone to get
	 * @param create
	 *            {@code true} to create a new Sone if none exists,
	 *            {@code false} to return {@code null} if a Sone with the given
	 *            ID does not exist
	 * @return The Sone with the given ID, or {@code null} if there is no such
	 *         Sone
	 */
	@Override
	public Sone getSone(String id, boolean create) {
		if (isLocalSone(id)) {
			return getLocalSone(id);
		}
		return getRemoteSone(id, create);
	}

	/**
	 * Checks whether the core knows a Sone with the given ID.
	 *
	 * @param id
	 *            The ID of the Sone
	 * @return {@code true} if there is a Sone with the given ID, {@code false}
	 *         otherwise
	 */
	public boolean hasSone(String id) {
		return isLocalSone(id) || isRemoteSone(id);
	}

	/**
	 * Returns whether the given Sone is a local Sone.
	 *
	 * @param sone
	 *            The Sone to check for its locality
	 * @return {@code true} if the given Sone is local, {@code false} otherwise
	 */
	public boolean isLocalSone(Sone sone) {
		synchronized (localSones) {
			return localSones.containsKey(sone.getId());
		}
	}

	/**
	 * Returns whether the given ID is the ID of a local Sone.
	 *
	 * @param id
	 *            The Sone ID to check for its locality
	 * @return {@code true} if the given ID is a local Sone, {@code false}
	 *         otherwise
	 */
	public boolean isLocalSone(String id) {
		synchronized (localSones) {
			return localSones.containsKey(id);
		}
	}

	/**
	 * Returns all local Sones.
	 *
	 * @return All local Sones
	 */
	public Set<Sone> getLocalSones() {
		synchronized (localSones) {
			return new HashSet<Sone>(localSones.values());
		}
	}

	/**
	 * Returns the local Sone with the given ID.
	 *
	 * @param id
	 *            The ID of the Sone to get
	 * @return The Sone with the given ID
	 */
	public Sone getLocalSone(String id) {
		return getLocalSone(id, true);
	}

	/**
	 * Returns the local Sone with the given ID, optionally creating a new Sone.
	 *
	 * @param id
	 *            The ID of the Sone
	 * @param create
	 *            {@code true} to create a new Sone if none exists,
	 *            {@code false} to return null if none exists
	 * @return The Sone with the given ID, or {@code null}
	 */
	public Sone getLocalSone(String id, boolean create) {
		synchronized (localSones) {
			Sone sone = localSones.get(id);
			if ((sone == null) && create) {
				sone = new Sone(id);
				localSones.put(id, sone);
			}
			return sone;
		}
	}

	/**
	 * Returns all remote Sones.
	 *
	 * @return All remote Sones
	 */
	public Set<Sone> getRemoteSones() {
		synchronized (remoteSones) {
			return new HashSet<Sone>(remoteSones.values());
		}
	}

	/**
	 * Returns the remote Sone with the given ID.
	 *
	 * @param id
	 *            The ID of the remote Sone to get
	 * @param create
	 *            {@code true} to always create a Sone, {@code false} to return
	 *            {@code null} if no Sone with the given ID exists
	 * @return The Sone with the given ID
	 */
	public Sone getRemoteSone(String id, boolean create) {
		synchronized (remoteSones) {
			Sone sone = remoteSones.get(id);
			if ((sone == null) && create && (id != null) && (id.length() == 43)) {
				sone = new Sone(id);
				remoteSones.put(id, sone);
			}
			return sone;
		}
	}

	/**
	 * Returns whether the given Sone is a remote Sone.
	 *
	 * @param sone
	 *            The Sone to check
	 * @return {@code true} if the given Sone is a remote Sone, {@code false}
	 *         otherwise
	 */
	public boolean isRemoteSone(Sone sone) {
		synchronized (remoteSones) {
			return remoteSones.containsKey(sone.getId());
		}
	}

	/**
	 * Returns whether the Sone with the given ID is a remote Sone.
	 *
	 * @param id
	 *            The ID of the Sone to check
	 * @return {@code true} if the Sone with the given ID is a remote Sone,
	 *         {@code false} otherwise
	 */
	public boolean isRemoteSone(String id) {
		synchronized (remoteSones) {
			return remoteSones.containsKey(id);
		}
	}

	/**
	 * Returns whether the given Sone has been modified.
	 *
	 * @param sone
	 *            The Sone to check for modifications
	 * @return {@code true} if a modification has been detected in the Sone,
	 *         {@code false} otherwise
	 */
	public boolean isModifiedSone(Sone sone) {
		return (soneInserters.containsKey(sone)) ? soneInserters.get(sone).isModified() : false;
	}

	/**
	 * Returns the time when the given was first followed by any local Sone.
	 *
	 * @param sone
	 *            The Sone to get the time for
	 * @return The time (in milliseconds since Jan 1, 1970) the Sone has first
	 *         been followed, or {@link Long#MAX_VALUE}
	 */
	public long getSoneFollowingTime(Sone sone) {
		synchronized (soneFollowingTimes) {
			if (soneFollowingTimes.containsKey(sone)) {
				return soneFollowingTimes.get(sone);
			}
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Returns whether the target Sone is trusted by the origin Sone.
	 *
	 * @param origin
	 *            The origin Sone
	 * @param target
	 *            The target Sone
	 * @return {@code true} if the target Sone is trusted by the origin Sone
	 */
	public boolean isSoneTrusted(Sone origin, Sone target) {
		Validation.begin().isNotNull("Origin", origin).isNotNull("Target", target).check().isInstanceOf("Origin’s OwnIdentity", origin.getIdentity(), OwnIdentity.class).check();
		return trustedIdentities.containsKey(origin.getIdentity()) && trustedIdentities.get(origin.getIdentity()).contains(target.getIdentity());
	}

	/**
	 * Returns the post with the given ID.
	 *
	 * @param postId
	 *            The ID of the post to get
	 * @return The post with the given ID, or a new post with the given ID
	 */
	public Post getPost(String postId) {
		return getPost(postId, true);
	}

	/**
	 * Returns the post with the given ID, optionally creating a new post.
	 *
	 * @param postId
	 *            The ID of the post to get
	 * @param create
	 *            {@code true} it create a new post if no post with the given ID
	 *            exists, {@code false} to return {@code null}
	 * @return The post, or {@code null} if there is no such post
	 */
	@Override
	public Post getPost(String postId, boolean create) {
		synchronized (posts) {
			Post post = posts.get(postId);
			if ((post == null) && create) {
				post = new PostImpl(postId);
				posts.put(postId, post);
			}
			return post;
		}
	}

	/**
	 * Returns all posts that have the given Sone as recipient.
	 *
	 * @see Post#getRecipient()
	 * @param recipient
	 *            The recipient of the posts
	 * @return All posts that have the given Sone as recipient
	 */
	public Set<Post> getDirectedPosts(Sone recipient) {
		Validation.begin().isNotNull("Recipient", recipient).check();
		Set<Post> directedPosts = new HashSet<Post>();
		synchronized (posts) {
			for (Post post : posts.values()) {
				if (recipient.equals(post.getRecipient())) {
					directedPosts.add(post);
				}
			}
		}
		return directedPosts;
	}

	/**
	 * Returns the reply with the given ID. If there is no reply with the given
	 * ID yet, a new one is created.
	 *
	 * @param replyId
	 *            The ID of the reply to get
	 * @return The reply
	 */
	public PostReply getReply(String replyId) {
		return getReply(replyId, true);
	}

	/**
	 * Returns the reply with the given ID. If there is no reply with the given
	 * ID yet, a new one is created, unless {@code create} is false in which
	 * case {@code null} is returned.
	 *
	 * @param replyId
	 *            The ID of the reply to get
	 * @param create
	 *            {@code true} to always return a {@link Reply}, {@code false}
	 *            to return {@code null} if no reply can be found
	 * @return The reply, or {@code null} if there is no such reply
	 */
	public PostReply getReply(String replyId, boolean create) {
		synchronized (replies) {
			PostReply reply = replies.get(replyId);
			if (create && (reply == null)) {
				reply = new PostReply(replyId);
				replies.put(replyId, reply);
			}
			return reply;
		}
	}

	/**
	 * Returns all replies for the given post, order ascending by time.
	 *
	 * @param post
	 *            The post to get all replies for
	 * @return All replies for the given post
	 */
	public List<PostReply> getReplies(Post post) {
		Set<Sone> sones = getSones();
		List<PostReply> replies = new ArrayList<PostReply>();
		for (Sone sone : sones) {
			for (PostReply reply : sone.getReplies()) {
				if (reply.getPost().equals(post)) {
					replies.add(reply);
				}
			}
		}
		Collections.sort(replies, Reply.TIME_COMPARATOR);
		return replies;
	}

	/**
	 * Returns all Sones that have liked the given post.
	 *
	 * @param post
	 *            The post to get the liking Sones for
	 * @return The Sones that like the given post
	 */
	public Set<Sone> getLikes(Post post) {
		Set<Sone> sones = new HashSet<Sone>();
		for (Sone sone : getSones()) {
			if (sone.getLikedPostIds().contains(post.getId())) {
				sones.add(sone);
			}
		}
		return sones;
	}

	/**
	 * Returns all Sones that have liked the given reply.
	 *
	 * @param reply
	 *            The reply to get the liking Sones for
	 * @return The Sones that like the given reply
	 */
	public Set<Sone> getLikes(PostReply reply) {
		Set<Sone> sones = new HashSet<Sone>();
		for (Sone sone : getSones()) {
			if (sone.getLikedReplyIds().contains(reply.getId())) {
				sones.add(sone);
			}
		}
		return sones;
	}

	/**
	 * Returns whether the given post is bookmarked.
	 *
	 * @param post
	 *            The post to check
	 * @return {@code true} if the given post is bookmarked, {@code false}
	 *         otherwise
	 */
	public boolean isBookmarked(Post post) {
		return isPostBookmarked(post.getId());
	}

	/**
	 * Returns whether the post with the given ID is bookmarked.
	 *
	 * @param id
	 *            The ID of the post to check
	 * @return {@code true} if the post with the given ID is bookmarked,
	 *         {@code false} otherwise
	 */
	public boolean isPostBookmarked(String id) {
		synchronized (bookmarkedPosts) {
			return bookmarkedPosts.contains(id);
		}
	}

	/**
	 * Returns all currently known bookmarked posts.
	 *
	 * @return All bookmarked posts
	 */
	public Set<Post> getBookmarkedPosts() {
		Set<Post> posts = new HashSet<Post>();
		synchronized (bookmarkedPosts) {
			for (String bookmarkedPostId : bookmarkedPosts) {
				Post post = getPost(bookmarkedPostId, false);
				if (post != null) {
					posts.add(post);
				}
			}
		}
		return posts;
	}

	/**
	 * Returns the album with the given ID, creating a new album if no album
	 * with the given ID can be found.
	 *
	 * @param albumId
	 *            The ID of the album
	 * @return The album with the given ID
	 */
	public Album getAlbum(String albumId) {
		return getAlbum(albumId, true);
	}

	/**
	 * Returns the album with the given ID, optionally creating a new album if
	 * an album with the given ID can not be found.
	 *
	 * @param albumId
	 *            The ID of the album
	 * @param create
	 *            {@code true} to create a new album if none exists for the
	 *            given ID
	 * @return The album with the given ID, or {@code null} if no album with the
	 *         given ID exists and {@code create} is {@code false}
	 */
	public Album getAlbum(String albumId, boolean create) {
		synchronized (albums) {
			Album album = albums.get(albumId);
			if (create && (album == null)) {
				album = new Album(albumId);
				albums.put(albumId, album);
			}
			return album;
		}
	}

	/**
	 * Returns the image with the given ID, creating it if necessary.
	 *
	 * @param imageId
	 *            The ID of the image
	 * @return The image with the given ID
	 */
	public Image getImage(String imageId) {
		return getImage(imageId, true);
	}

	/**
	 * Returns the image with the given ID, optionally creating it if it does
	 * not exist.
	 *
	 * @param imageId
	 *            The ID of the image
	 * @param create
	 *            {@code true} to create an image if none exists with the given
	 *            ID
	 * @return The image with the given ID, or {@code null} if none exists and
	 *         none was created
	 */
	public Image getImage(String imageId, boolean create) {
		synchronized (images) {
			Image image = images.get(imageId);
			if (create && (image == null)) {
				image = new Image(imageId);
				images.put(imageId, image);
			}
			return image;
		}
	}

	/**
	 * Returns the temporary image with the given ID.
	 *
	 * @param imageId
	 *            The ID of the temporary image
	 * @return The temporary image, or {@code null} if there is no temporary
	 *         image with the given ID
	 */
	public TemporaryImage getTemporaryImage(String imageId) {
		synchronized (temporaryImages) {
			return temporaryImages.get(imageId);
		}
	}

	//
	// ACTIONS
	//

	/**
	 * Locks the given Sone. A locked Sone will not be inserted by
	 * {@link SoneInserter} until it is {@link #unlockSone(Sone) unlocked}
	 * again.
	 *
	 * @param sone
	 *            The sone to lock
	 */
	public void lockSone(Sone sone) {
		synchronized (lockedSones) {
			if (lockedSones.add(sone)) {
				coreListenerManager.fireSoneLocked(sone);
			}
		}
	}

	/**
	 * Unlocks the given Sone.
	 *
	 * @see #lockSone(Sone)
	 * @param sone
	 *            The sone to unlock
	 */
	public void unlockSone(Sone sone) {
		synchronized (lockedSones) {
			if (lockedSones.remove(sone)) {
				coreListenerManager.fireSoneUnlocked(sone);
			}
		}
	}

	/**
	 * Adds a local Sone from the given own identity.
	 *
	 * @param ownIdentity
	 *            The own identity to create a Sone from
	 * @return The added (or already existing) Sone
	 */
	public Sone addLocalSone(OwnIdentity ownIdentity) {
		if (ownIdentity == null) {
			logger.log(Level.WARNING, "Given OwnIdentity is null!");
			return null;
		}
		synchronized (localSones) {
			final Sone sone;
			try {
				sone = getLocalSone(ownIdentity.getId()).setIdentity(ownIdentity).setInsertUri(new FreenetURI(ownIdentity.getInsertUri())).setRequestUri(new FreenetURI(ownIdentity.getRequestUri()));
			} catch (MalformedURLException mue1) {
				logger.log(Level.SEVERE, String.format("Could not convert the Identity’s URIs to Freenet URIs: %s, %s", ownIdentity.getInsertUri(), ownIdentity.getRequestUri()), mue1);
				return null;
			}
			sone.setLatestEdition(Numbers.safeParseLong(ownIdentity.getProperty("Sone.LatestEdition"), (long) 0));
			sone.setClient(new Client("Sone", SonePlugin.VERSION.toString()));
			sone.setKnown(true);
			/* TODO - load posts ’n stuff */
			localSones.put(ownIdentity.getId(), sone);
			final SoneInserter soneInserter = new SoneInserter(this, freenetInterface, sone);
			soneInserter.addSoneInsertListener(this);
			soneInserters.put(sone, soneInserter);
			sone.setStatus(SoneStatus.idle);
			loadSone(sone);
			soneInserter.start();
			return sone;
		}
	}

	/**
	 * Creates a new Sone for the given own identity.
	 *
	 * @param ownIdentity
	 *            The own identity to create a Sone for
	 * @return The created Sone
	 */
	public Sone createSone(OwnIdentity ownIdentity) {
		if (!webOfTrustUpdater.addContextWait(ownIdentity, "Sone")) {
			logger.log(Level.SEVERE, String.format("Could not add “Sone” context to own identity: %s", ownIdentity));
			return null;
		}
		Sone sone = addLocalSone(ownIdentity);
		sone.getOptions().addBooleanOption("AutoFollow", new DefaultOption<Boolean>(false));
		sone.getOptions().addBooleanOption("EnableSoneInsertNotifications", new DefaultOption<Boolean>(false));
		sone.getOptions().addBooleanOption("ShowNotification/NewSones", new DefaultOption<Boolean>(true));
		sone.getOptions().addBooleanOption("ShowNotification/NewPosts", new DefaultOption<Boolean>(true));
		sone.getOptions().addBooleanOption("ShowNotification/NewReplies", new DefaultOption<Boolean>(true));
		sone.getOptions().addEnumOption("ShowCustomAvatars", new DefaultOption<ShowCustomAvatars>(ShowCustomAvatars.NEVER));

		followSone(sone, getSone("nwa8lHa271k2QvJ8aa0Ov7IHAV-DFOCFgmDt3X6BpCI"));
		touchConfiguration();
		return sone;
	}

	/**
	 * Adds the Sone of the given identity.
	 *
	 * @param identity
	 *            The identity whose Sone to add
	 * @return The added or already existing Sone
	 */
	public Sone addRemoteSone(Identity identity) {
		if (identity == null) {
			logger.log(Level.WARNING, "Given Identity is null!");
			return null;
		}
		synchronized (remoteSones) {
			final Sone sone = getRemoteSone(identity.getId(), true).setIdentity(identity);
			boolean newSone = sone.getRequestUri() == null;
			sone.setRequestUri(getSoneUri(identity.getRequestUri()));
			sone.setLatestEdition(Numbers.safeParseLong(identity.getProperty("Sone.LatestEdition"), (long) 0));
			if (newSone) {
				synchronized (knownSones) {
					newSone = !knownSones.contains(sone.getId());
				}
				sone.setKnown(!newSone);
				if (newSone) {
					coreListenerManager.fireNewSoneFound(sone);
					for (Sone localSone : getLocalSones()) {
						if (localSone.getOptions().getBooleanOption("AutoFollow").get()) {
							followSone(localSone, sone);
						}
					}
				}
			}
			soneDownloader.addSone(sone);
			soneDownloaders.execute(new Runnable() {

				@Override
				@SuppressWarnings("synthetic-access")
				public void run() {
					soneDownloader.fetchSone(sone, sone.getRequestUri());
				}

			});
			return sone;
		}
	}

	/**
	 * Lets the given local Sone follow the Sone with the given ID.
	 *
	 * @param sone
	 *            The local Sone that should follow another Sone
	 * @param soneId
	 *            The ID of the Sone to follow
	 */
	public void followSone(Sone sone, String soneId) {
		Validation.begin().isNotNull("Sone", sone).isNotNull("Sone ID", soneId).check();
		Sone followedSone = getSone(soneId, true);
		if (followedSone == null) {
			logger.log(Level.INFO, String.format("Ignored Sone with invalid ID: %s", soneId));
			return;
		}
		followSone(sone, getSone(soneId));
	}

	/**
	 * Lets the given local Sone follow the other given Sone. If the given Sone
	 * was not followed by any local Sone before, this will mark all elements of
	 * the followed Sone as read that have been created before the current
	 * moment.
	 *
	 * @param sone
	 *            The local Sone that should follow the other Sone
	 * @param followedSone
	 *            The Sone that should be followed
	 */
	public void followSone(Sone sone, Sone followedSone) {
		Validation.begin().isNotNull("Sone", sone).isNotNull("Followed Sone", followedSone).check();
		sone.addFriend(followedSone.getId());
		synchronized (soneFollowingTimes) {
			if (!soneFollowingTimes.containsKey(followedSone)) {
				long now = System.currentTimeMillis();
				soneFollowingTimes.put(followedSone, now);
				for (Post post : followedSone.getPosts()) {
					if (post.getTime() < now) {
						markPostKnown(post);
					}
				}
				for (PostReply reply : followedSone.getReplies()) {
					if (reply.getTime() < now) {
						markReplyKnown(reply);
					}
				}
			}
		}
		touchConfiguration();
	}

	/**
	 * Lets the given local Sone unfollow the Sone with the given ID.
	 *
	 * @param sone
	 *            The local Sone that should unfollow another Sone
	 * @param soneId
	 *            The ID of the Sone being unfollowed
	 */
	public void unfollowSone(Sone sone, String soneId) {
		Validation.begin().isNotNull("Sone", sone).isNotNull("Sone ID", soneId).check();
		unfollowSone(sone, getSone(soneId, false));
	}

	/**
	 * Lets the given local Sone unfollow the other given Sone. If the given
	 * local Sone is the last local Sone that followed the given Sone, its
	 * following time will be removed.
	 *
	 * @param sone
	 *            The local Sone that should unfollow another Sone
	 * @param unfollowedSone
	 *            The Sone being unfollowed
	 */
	public void unfollowSone(Sone sone, Sone unfollowedSone) {
		Validation.begin().isNotNull("Sone", sone).isNotNull("Unfollowed Sone", unfollowedSone).check();
		sone.removeFriend(unfollowedSone.getId());
		boolean unfollowedSoneStillFollowed = false;
		for (Sone localSone : getLocalSones()) {
			unfollowedSoneStillFollowed |= localSone.hasFriend(unfollowedSone.getId());
		}
		if (!unfollowedSoneStillFollowed) {
			synchronized (soneFollowingTimes) {
				soneFollowingTimes.remove(unfollowedSone);
			}
		}
		touchConfiguration();
	}

	/**
	 * Sets the trust value of the given origin Sone for the target Sone.
	 *
	 * @param origin
	 *            The origin Sone
	 * @param target
	 *            The target Sone
	 * @param trustValue
	 *            The trust value (from {@code -100} to {@code 100})
	 */
	public void setTrust(Sone origin, Sone target, int trustValue) {
		Validation.begin().isNotNull("Trust Origin", origin).check().isInstanceOf("Trust Origin", origin.getIdentity(), OwnIdentity.class).isNotNull("Trust Target", target).isLessOrEqual("Trust Value", trustValue, 100).isGreaterOrEqual("Trust Value", trustValue, -100).check();
		webOfTrustUpdater.setTrust((OwnIdentity) origin.getIdentity(), target.getIdentity(), trustValue, preferences.getTrustComment());
	}

	/**
	 * Removes any trust assignment for the given target Sone.
	 *
	 * @param origin
	 *            The trust origin
	 * @param target
	 *            The trust target
	 */
	public void removeTrust(Sone origin, Sone target) {
		Validation.begin().isNotNull("Trust Origin", origin).isNotNull("Trust Target", target).check().isInstanceOf("Trust Origin Identity", origin.getIdentity(), OwnIdentity.class).check();
		webOfTrustUpdater.setTrust((OwnIdentity) origin.getIdentity(), target.getIdentity(), null, null);
	}

	/**
	 * Assigns the configured positive trust value for the given target.
	 *
	 * @param origin
	 *            The trust origin
	 * @param target
	 *            The trust target
	 */
	public void trustSone(Sone origin, Sone target) {
		setTrust(origin, target, preferences.getPositiveTrust());
	}

	/**
	 * Assigns the configured negative trust value for the given target.
	 *
	 * @param origin
	 *            The trust origin
	 * @param target
	 *            The trust target
	 */
	public void distrustSone(Sone origin, Sone target) {
		setTrust(origin, target, preferences.getNegativeTrust());
	}

	/**
	 * Removes the trust assignment for the given target.
	 *
	 * @param origin
	 *            The trust origin
	 * @param target
	 *            The trust target
	 */
	public void untrustSone(Sone origin, Sone target) {
		removeTrust(origin, target);
	}

	/**
	 * Updates the stored Sone with the given Sone.
	 *
	 * @param sone
	 *            The updated Sone
	 */
	public void updateSone(Sone sone) {
		updateSone(sone, false);
	}

	/**
	 * Updates the stored Sone with the given Sone. If {@code soneRescueMode} is
	 * {@code true}, an older Sone than the current Sone can be given to restore
	 * an old state.
	 *
	 * @param sone
	 *            The Sone to update
	 * @param soneRescueMode
	 *            {@code true} if the stored Sone should be updated regardless
	 *            of the age of the given Sone
	 */
	public void updateSone(Sone sone, boolean soneRescueMode) {
		if (hasSone(sone.getId())) {
			Sone storedSone = getSone(sone.getId());
			if (!soneRescueMode && !(sone.getTime() > storedSone.getTime())) {
				logger.log(Level.FINE, String.format("Downloaded Sone %s is not newer than stored Sone %s.", sone, storedSone));
				return;
			}
			synchronized (posts) {
				if (!soneRescueMode) {
					for (Post post : storedSone.getPosts()) {
						posts.remove(post.getId());
						if (!sone.getPosts().contains(post)) {
							coreListenerManager.firePostRemoved(post);
						}
					}
				}
				List<Post> storedPosts = storedSone.getPosts();
				synchronized (knownPosts) {
					for (Post post : sone.getPosts()) {
						post.setSone(storedSone).setKnown(knownPosts.contains(post.getId()));
						if (!storedPosts.contains(post)) {
							if (post.getTime() < getSoneFollowingTime(sone)) {
								knownPosts.add(post.getId());
							} else if (!knownPosts.contains(post.getId())) {
								sone.setKnown(false);
								coreListenerManager.fireNewPostFound(post);
							}
						}
						posts.put(post.getId(), post);
					}
				}
			}
			synchronized (replies) {
				if (!soneRescueMode) {
					for (PostReply reply : storedSone.getReplies()) {
						replies.remove(reply.getId());
						if (!sone.getReplies().contains(reply)) {
							coreListenerManager.fireReplyRemoved(reply);
						}
					}
				}
				Set<PostReply> storedReplies = storedSone.getReplies();
				synchronized (knownReplies) {
					for (PostReply reply : sone.getReplies()) {
						reply.setSone(storedSone).setKnown(knownReplies.contains(reply.getId()));
						if (!storedReplies.contains(reply)) {
							if (reply.getTime() < getSoneFollowingTime(sone)) {
								knownReplies.add(reply.getId());
							} else if (!knownReplies.contains(reply.getId())) {
								reply.setKnown(false);
								coreListenerManager.fireNewReplyFound(reply);
							}
						}
						replies.put(reply.getId(), reply);
					}
				}
			}
			synchronized (albums) {
				synchronized (images) {
					for (Album album : storedSone.getAlbums()) {
						albums.remove(album.getId());
						for (Image image : album.getImages()) {
							images.remove(image.getId());
						}
					}
					for (Album album : sone.getAlbums()) {
						albums.put(album.getId(), album);
						for (Image image : album.getImages()) {
							images.put(image.getId(), image);
						}
					}
				}
			}
			synchronized (storedSone) {
				if (!soneRescueMode || (sone.getTime() > storedSone.getTime())) {
					storedSone.setTime(sone.getTime());
				}
				storedSone.setClient(sone.getClient());
				storedSone.setProfile(sone.getProfile());
				if (soneRescueMode) {
					for (Post post : sone.getPosts()) {
						storedSone.addPost(post);
					}
					for (PostReply reply : sone.getReplies()) {
						storedSone.addReply(reply);
					}
					for (String likedPostId : sone.getLikedPostIds()) {
						storedSone.addLikedPostId(likedPostId);
					}
					for (String likedReplyId : sone.getLikedReplyIds()) {
						storedSone.addLikedReplyId(likedReplyId);
					}
					for (Album album : sone.getAlbums()) {
						storedSone.addAlbum(album);
					}
				} else {
					storedSone.setPosts(sone.getPosts());
					storedSone.setReplies(sone.getReplies());
					storedSone.setLikePostIds(sone.getLikedPostIds());
					storedSone.setLikeReplyIds(sone.getLikedReplyIds());
					storedSone.setAlbums(sone.getAlbums());
				}
				storedSone.setLatestEdition(sone.getLatestEdition());
			}
		}
	}

	/**
	 * Deletes the given Sone. This will remove the Sone from the
	 * {@link #getLocalSone(String) local Sones}, stops its {@link SoneInserter}
	 * and remove the context from its identity.
	 *
	 * @param sone
	 *            The Sone to delete
	 */
	public void deleteSone(Sone sone) {
		if (!(sone.getIdentity() instanceof OwnIdentity)) {
			logger.log(Level.WARNING, String.format("Tried to delete Sone of non-own identity: %s", sone));
			return;
		}
		synchronized (localSones) {
			if (!localSones.containsKey(sone.getId())) {
				logger.log(Level.WARNING, String.format("Tried to delete non-local Sone: %s", sone));
				return;
			}
			localSones.remove(sone.getId());
			SoneInserter soneInserter = soneInserters.remove(sone);
			soneInserter.removeSoneInsertListener(this);
			soneInserter.stop();
		}
		webOfTrustUpdater.removeContext((OwnIdentity) sone.getIdentity(), "Sone");
		webOfTrustUpdater.removeProperty((OwnIdentity) sone.getIdentity(), "Sone.LatestEdition");
		try {
			configuration.getLongValue("Sone/" + sone.getId() + "/Time").setValue(null);
		} catch (ConfigurationException ce1) {
			logger.log(Level.WARNING, "Could not remove Sone from configuration!", ce1);
		}
	}

	/**
	 * Marks the given Sone as known. If the Sone was not {@link Post#isKnown()
	 * known} before, a {@link CoreListener#markSoneKnown(Sone)} event is fired.
	 *
	 * @param sone
	 *            The Sone to mark as known
	 */
	public void markSoneKnown(Sone sone) {
		if (!sone.isKnown()) {
			sone.setKnown(true);
			synchronized (knownSones) {
				knownSones.add(sone.getId());
			}
			coreListenerManager.fireMarkSoneKnown(sone);
			touchConfiguration();
		}
	}

	/**
	 * Loads and updates the given Sone from the configuration. If any error is
	 * encountered, loading is aborted and the given Sone is not changed.
	 *
	 * @param sone
	 *            The Sone to load and update
	 */
	public void loadSone(Sone sone) {
		if (!isLocalSone(sone)) {
			logger.log(Level.FINE, String.format("Tried to load non-local Sone: %s", sone));
			return;
		}

		/* initialize options. */
		sone.getOptions().addBooleanOption("AutoFollow", new DefaultOption<Boolean>(false));
		sone.getOptions().addBooleanOption("EnableSoneInsertNotifications", new DefaultOption<Boolean>(false));
		sone.getOptions().addBooleanOption("ShowNotification/NewSones", new DefaultOption<Boolean>(true));
		sone.getOptions().addBooleanOption("ShowNotification/NewPosts", new DefaultOption<Boolean>(true));
		sone.getOptions().addBooleanOption("ShowNotification/NewReplies", new DefaultOption<Boolean>(true));
		sone.getOptions().addEnumOption("ShowCustomAvatars", new DefaultOption<ShowCustomAvatars>(ShowCustomAvatars.NEVER));

		/* load Sone. */
		String sonePrefix = "Sone/" + sone.getId();
		Long soneTime = configuration.getLongValue(sonePrefix + "/Time").getValue(null);
		if (soneTime == null) {
			logger.log(Level.INFO, "Could not load Sone because no Sone has been saved.");
			return;
		}
		String lastInsertFingerprint = configuration.getStringValue(sonePrefix + "/LastInsertFingerprint").getValue("");

		/* load profile. */
		Profile profile = new Profile(sone);
		profile.setFirstName(configuration.getStringValue(sonePrefix + "/Profile/FirstName").getValue(null));
		profile.setMiddleName(configuration.getStringValue(sonePrefix + "/Profile/MiddleName").getValue(null));
		profile.setLastName(configuration.getStringValue(sonePrefix + "/Profile/LastName").getValue(null));
		profile.setBirthDay(configuration.getIntValue(sonePrefix + "/Profile/BirthDay").getValue(null));
		profile.setBirthMonth(configuration.getIntValue(sonePrefix + "/Profile/BirthMonth").getValue(null));
		profile.setBirthYear(configuration.getIntValue(sonePrefix + "/Profile/BirthYear").getValue(null));

		/* load profile fields. */
		while (true) {
			String fieldPrefix = sonePrefix + "/Profile/Fields/" + profile.getFields().size();
			String fieldName = configuration.getStringValue(fieldPrefix + "/Name").getValue(null);
			if (fieldName == null) {
				break;
			}
			String fieldValue = configuration.getStringValue(fieldPrefix + "/Value").getValue("");
			profile.addField(fieldName).setValue(fieldValue);
		}

		/* load posts. */
		Set<Post> posts = new HashSet<Post>();
		while (true) {
			String postPrefix = sonePrefix + "/Posts/" + posts.size();
			String postId = configuration.getStringValue(postPrefix + "/ID").getValue(null);
			if (postId == null) {
				break;
			}
			String postRecipientId = configuration.getStringValue(postPrefix + "/Recipient").getValue(null);
			long postTime = configuration.getLongValue(postPrefix + "/Time").getValue((long) 0);
			String postText = configuration.getStringValue(postPrefix + "/Text").getValue(null);
			if ((postTime == 0) || (postText == null)) {
				logger.log(Level.WARNING, "Invalid post found, aborting load!");
				return;
			}
			Post post = getPost(postId).setSone(sone).setTime(postTime).setText(postText);
			if ((postRecipientId != null) && (postRecipientId.length() == 43)) {
				post.setRecipient(getSone(postRecipientId));
			}
			posts.add(post);
		}

		/* load replies. */
		Set<PostReply> replies = new HashSet<PostReply>();
		while (true) {
			String replyPrefix = sonePrefix + "/Replies/" + replies.size();
			String replyId = configuration.getStringValue(replyPrefix + "/ID").getValue(null);
			if (replyId == null) {
				break;
			}
			String postId = configuration.getStringValue(replyPrefix + "/Post/ID").getValue(null);
			long replyTime = configuration.getLongValue(replyPrefix + "/Time").getValue((long) 0);
			String replyText = configuration.getStringValue(replyPrefix + "/Text").getValue(null);
			if ((postId == null) || (replyTime == 0) || (replyText == null)) {
				logger.log(Level.WARNING, "Invalid reply found, aborting load!");
				return;
			}
			replies.add(getReply(replyId).setSone(sone).setPost(getPost(postId)).setTime(replyTime).setText(replyText));
		}

		/* load post likes. */
		Set<String> likedPostIds = new HashSet<String>();
		while (true) {
			String likedPostId = configuration.getStringValue(sonePrefix + "/Likes/Post/" + likedPostIds.size() + "/ID").getValue(null);
			if (likedPostId == null) {
				break;
			}
			likedPostIds.add(likedPostId);
		}

		/* load reply likes. */
		Set<String> likedReplyIds = new HashSet<String>();
		while (true) {
			String likedReplyId = configuration.getStringValue(sonePrefix + "/Likes/Reply/" + likedReplyIds.size() + "/ID").getValue(null);
			if (likedReplyId == null) {
				break;
			}
			likedReplyIds.add(likedReplyId);
		}

		/* load friends. */
		Set<String> friends = new HashSet<String>();
		while (true) {
			String friendId = configuration.getStringValue(sonePrefix + "/Friends/" + friends.size() + "/ID").getValue(null);
			if (friendId == null) {
				break;
			}
			friends.add(friendId);
		}

		/* load albums. */
		List<Album> topLevelAlbums = new ArrayList<Album>();
		int albumCounter = 0;
		while (true) {
			String albumPrefix = sonePrefix + "/Albums/" + albumCounter++;
			String albumId = configuration.getStringValue(albumPrefix + "/ID").getValue(null);
			if (albumId == null) {
				break;
			}
			String albumTitle = configuration.getStringValue(albumPrefix + "/Title").getValue(null);
			String albumDescription = configuration.getStringValue(albumPrefix + "/Description").getValue(null);
			String albumParentId = configuration.getStringValue(albumPrefix + "/Parent").getValue(null);
			String albumImageId = configuration.getStringValue(albumPrefix + "/AlbumImage").getValue(null);
			if ((albumTitle == null) || (albumDescription == null)) {
				logger.log(Level.WARNING, "Invalid album found, aborting load!");
				return;
			}
			Album album = getAlbum(albumId).setSone(sone).setTitle(albumTitle).setDescription(albumDescription).setAlbumImage(albumImageId);
			if (albumParentId != null) {
				Album parentAlbum = getAlbum(albumParentId, false);
				if (parentAlbum == null) {
					logger.log(Level.WARNING, String.format("Invalid parent album ID: %s", albumParentId));
					return;
				}
				parentAlbum.addAlbum(album);
			} else {
				if (!topLevelAlbums.contains(album)) {
					topLevelAlbums.add(album);
				}
			}
		}

		/* load images. */
		int imageCounter = 0;
		while (true) {
			String imagePrefix = sonePrefix + "/Images/" + imageCounter++;
			String imageId = configuration.getStringValue(imagePrefix + "/ID").getValue(null);
			if (imageId == null) {
				break;
			}
			String albumId = configuration.getStringValue(imagePrefix + "/Album").getValue(null);
			String key = configuration.getStringValue(imagePrefix + "/Key").getValue(null);
			String title = configuration.getStringValue(imagePrefix + "/Title").getValue(null);
			String description = configuration.getStringValue(imagePrefix + "/Description").getValue(null);
			Long creationTime = configuration.getLongValue(imagePrefix + "/CreationTime").getValue(null);
			Integer width = configuration.getIntValue(imagePrefix + "/Width").getValue(null);
			Integer height = configuration.getIntValue(imagePrefix + "/Height").getValue(null);
			if ((albumId == null) || (key == null) || (title == null) || (description == null) || (creationTime == null) || (width == null) || (height == null)) {
				logger.log(Level.WARNING, "Invalid image found, aborting load!");
				return;
			}
			Album album = getAlbum(albumId, false);
			if (album == null) {
				logger.log(Level.WARNING, "Invalid album image encountered, aborting load!");
				return;
			}
			Image image = getImage(imageId).setSone(sone).setCreationTime(creationTime).setKey(key);
			image.setTitle(title).setDescription(description).setWidth(width).setHeight(height);
			album.addImage(image);
		}

		/* load avatar. */
		String avatarId = configuration.getStringValue(sonePrefix + "/Profile/Avatar").getValue(null);
		if (avatarId != null) {
			profile.setAvatar(getImage(avatarId, false));
		}

		/* load options. */
		sone.getOptions().getBooleanOption("AutoFollow").set(configuration.getBooleanValue(sonePrefix + "/Options/AutoFollow").getValue(null));
		sone.getOptions().getBooleanOption("EnableSoneInsertNotifications").set(configuration.getBooleanValue(sonePrefix + "/Options/EnableSoneInsertNotifications").getValue(null));
		sone.getOptions().getBooleanOption("ShowNotification/NewSones").set(configuration.getBooleanValue(sonePrefix + "/Options/ShowNotification/NewSones").getValue(null));
		sone.getOptions().getBooleanOption("ShowNotification/NewPosts").set(configuration.getBooleanValue(sonePrefix + "/Options/ShowNotification/NewPosts").getValue(null));
		sone.getOptions().getBooleanOption("ShowNotification/NewReplies").set(configuration.getBooleanValue(sonePrefix + "/Options/ShowNotification/NewReplies").getValue(null));
		sone.getOptions().<ShowCustomAvatars> getEnumOption("ShowCustomAvatars").set(ShowCustomAvatars.valueOf(configuration.getStringValue(sonePrefix + "/Options/ShowCustomAvatars").getValue(ShowCustomAvatars.NEVER.name())));

		/* if we’re still here, Sone was loaded successfully. */
		synchronized (sone) {
			sone.setTime(soneTime);
			sone.setProfile(profile);
			sone.setPosts(posts);
			sone.setReplies(replies);
			sone.setLikePostIds(likedPostIds);
			sone.setLikeReplyIds(likedReplyIds);
			for (String friendId : friends) {
				followSone(sone, friendId);
			}
			sone.setAlbums(topLevelAlbums);
			soneInserters.get(sone).setLastInsertFingerprint(lastInsertFingerprint);
		}
		synchronized (knownSones) {
			for (String friend : friends) {
				knownSones.add(friend);
			}
		}
		synchronized (knownPosts) {
			for (Post post : posts) {
				knownPosts.add(post.getId());
			}
		}
		synchronized (knownReplies) {
			for (PostReply reply : replies) {
				knownReplies.add(reply.getId());
			}
		}
	}

	/**
	 * Creates a new post.
	 *
	 * @param sone
	 *            The Sone that creates the post
	 * @param text
	 *            The text of the post
	 * @return The created post
	 */
	public Post createPost(Sone sone, String text) {
		return createPost(sone, System.currentTimeMillis(), text);
	}

	/**
	 * Creates a new post.
	 *
	 * @param sone
	 *            The Sone that creates the post
	 * @param time
	 *            The time of the post
	 * @param text
	 *            The text of the post
	 * @return The created post
	 */
	public Post createPost(Sone sone, long time, String text) {
		return createPost(sone, null, time, text);
	}

	/**
	 * Creates a new post.
	 *
	 * @param sone
	 *            The Sone that creates the post
	 * @param recipient
	 *            The recipient Sone, or {@code null} if this post does not have
	 *            a recipient
	 * @param text
	 *            The text of the post
	 * @return The created post
	 */
	public Post createPost(Sone sone, Sone recipient, String text) {
		return createPost(sone, recipient, System.currentTimeMillis(), text);
	}

	/**
	 * Creates a new post.
	 *
	 * @param sone
	 *            The Sone that creates the post
	 * @param recipient
	 *            The recipient Sone, or {@code null} if this post does not have
	 *            a recipient
	 * @param time
	 *            The time of the post
	 * @param text
	 *            The text of the post
	 * @return The created post
	 */
	public Post createPost(Sone sone, Sone recipient, long time, String text) {
		if (!isLocalSone(sone)) {
			logger.log(Level.FINE, String.format("Tried to create post for non-local Sone: %s", sone));
			return null;
		}
		final Post post = new PostImpl(sone, time, text);
		if (recipient != null) {
			post.setRecipient(recipient);
		}
		synchronized (posts) {
			posts.put(post.getId(), post);
		}
		coreListenerManager.fireNewPostFound(post);
		sone.addPost(post);
		touchConfiguration();
		localElementTicker.registerEvent(System.currentTimeMillis() + 10 * 1000, new Runnable() {

			/**
			 * {@inheritDoc}
			 */
			@Override
			public void run() {
				markPostKnown(post);
			}
		}, "Mark " + post + " read.");
		return post;
	}

	/**
	 * Deletes the given post.
	 *
	 * @param post
	 *            The post to delete
	 */
	public void deletePost(Post post) {
		if (!isLocalSone(post.getSone())) {
			logger.log(Level.WARNING, String.format("Tried to delete post of non-local Sone: %s", post.getSone()));
			return;
		}
		post.getSone().removePost(post);
		synchronized (posts) {
			posts.remove(post.getId());
		}
		coreListenerManager.firePostRemoved(post);
		markPostKnown(post);
		touchConfiguration();
	}

	/**
	 * Marks the given post as known, if it is currently not a known post
	 * (according to {@link Post#isKnown()}).
	 *
	 * @param post
	 *            The post to mark as known
	 */
	public void markPostKnown(Post post) {
		post.setKnown(true);
		synchronized (knownPosts) {
			coreListenerManager.fireMarkPostKnown(post);
			if (knownPosts.add(post.getId())) {
				touchConfiguration();
			}
		}
		for (PostReply reply : getReplies(post)) {
			markReplyKnown(reply);
		}
	}

	/**
	 * Bookmarks the given post.
	 *
	 * @param post
	 *            The post to bookmark
	 */
	public void bookmark(Post post) {
		bookmarkPost(post.getId());
	}

	/**
	 * Bookmarks the post with the given ID.
	 *
	 * @param id
	 *            The ID of the post to bookmark
	 */
	public void bookmarkPost(String id) {
		synchronized (bookmarkedPosts) {
			bookmarkedPosts.add(id);
		}
	}

	/**
	 * Removes the given post from the bookmarks.
	 *
	 * @param post
	 *            The post to unbookmark
	 */
	public void unbookmark(Post post) {
		unbookmarkPost(post.getId());
	}

	/**
	 * Removes the post with the given ID from the bookmarks.
	 *
	 * @param id
	 *            The ID of the post to unbookmark
	 */
	public void unbookmarkPost(String id) {
		synchronized (bookmarkedPosts) {
			bookmarkedPosts.remove(id);
		}
	}

	/**
	 * Creates a new reply.
	 *
	 * @param sone
	 *            The Sone that creates the reply
	 * @param post
	 *            The post that this reply refers to
	 * @param text
	 *            The text of the reply
	 * @return The created reply
	 */
	public PostReply createReply(Sone sone, Post post, String text) {
		return createReply(sone, post, System.currentTimeMillis(), text);
	}

	/**
	 * Creates a new reply.
	 *
	 * @param sone
	 *            The Sone that creates the reply
	 * @param post
	 *            The post that this reply refers to
	 * @param time
	 *            The time of the reply
	 * @param text
	 *            The text of the reply
	 * @return The created reply
	 */
	public PostReply createReply(Sone sone, Post post, long time, String text) {
		if (!isLocalSone(sone)) {
			logger.log(Level.FINE, String.format("Tried to create reply for non-local Sone: %s", sone));
			return null;
		}
		final PostReply reply = new PostReply(sone, post, System.currentTimeMillis(), text);
		synchronized (replies) {
			replies.put(reply.getId(), reply);
		}
		synchronized (knownReplies) {
			coreListenerManager.fireNewReplyFound(reply);
		}
		sone.addReply(reply);
		touchConfiguration();
		localElementTicker.registerEvent(System.currentTimeMillis() + 10 * 1000, new Runnable() {

			/**
			 * {@inheritDoc}
			 */
			@Override
			public void run() {
				markReplyKnown(reply);
			}
		}, "Mark " + reply + " read.");
		return reply;
	}

	/**
	 * Deletes the given reply.
	 *
	 * @param reply
	 *            The reply to delete
	 */
	public void deleteReply(PostReply reply) {
		Sone sone = reply.getSone();
		if (!isLocalSone(sone)) {
			logger.log(Level.FINE, String.format("Tried to delete non-local reply: %s", reply));
			return;
		}
		synchronized (replies) {
			replies.remove(reply.getId());
		}
		synchronized (knownReplies) {
			markReplyKnown(reply);
			knownReplies.remove(reply.getId());
		}
		sone.removeReply(reply);
		touchConfiguration();
	}

	/**
	 * Marks the given reply as known, if it is currently not a known reply
	 * (according to {@link Reply#isKnown()}).
	 *
	 * @param reply
	 *            The reply to mark as known
	 */
	public void markReplyKnown(PostReply reply) {
		reply.setKnown(true);
		synchronized (knownReplies) {
			coreListenerManager.fireMarkReplyKnown(reply);
			if (knownReplies.add(reply.getId())) {
				touchConfiguration();
			}
		}
	}

	/**
	 * Creates a new top-level album for the given Sone.
	 *
	 * @param sone
	 *            The Sone to create the album for
	 * @return The new album
	 */
	public Album createAlbum(Sone sone) {
		return createAlbum(sone, null);
	}

	/**
	 * Creates a new album for the given Sone.
	 *
	 * @param sone
	 *            The Sone to create the album for
	 * @param parent
	 *            The parent of the album (may be {@code null} to create a
	 *            top-level album)
	 * @return The new album
	 */
	public Album createAlbum(Sone sone, Album parent) {
		Album album = new Album();
		synchronized (albums) {
			albums.put(album.getId(), album);
		}
		album.setSone(sone);
		if (parent != null) {
			parent.addAlbum(album);
		} else {
			sone.addAlbum(album);
		}
		return album;
	}

	/**
	 * Deletes the given album. The owner of the album has to be a local Sone,
	 * and the album has to be {@link Album#isEmpty() empty} to be deleted.
	 *
	 * @param album
	 *            The album to remove
	 */
	public void deleteAlbum(Album album) {
		Validation.begin().isNotNull("Album", album).check().is("Local Sone", isLocalSone(album.getSone())).check();
		if (!album.isEmpty()) {
			return;
		}
		if (album.getParent() == null) {
			album.getSone().removeAlbum(album);
		} else {
			album.getParent().removeAlbum(album);
		}
		synchronized (albums) {
			albums.remove(album.getId());
		}
		touchConfiguration();
	}

	/**
	 * Creates a new image.
	 *
	 * @param sone
	 *            The Sone creating the image
	 * @param album
	 *            The album the image will be inserted into
	 * @param temporaryImage
	 *            The temporary image to create the image from
	 * @return The newly created image
	 */
	public Image createImage(Sone sone, Album album, TemporaryImage temporaryImage) {
		Validation.begin().isNotNull("Sone", sone).isNotNull("Album", album).isNotNull("Temporary Image", temporaryImage).check().is("Local Sone", isLocalSone(sone)).check().isEqual("Owner and Album Owner", sone, album.getSone()).check();
		Image image = new Image(temporaryImage.getId()).setSone(sone).setCreationTime(System.currentTimeMillis());
		album.addImage(image);
		synchronized (images) {
			images.put(image.getId(), image);
		}
		imageInserter.insertImage(temporaryImage, image);
		return image;
	}

	/**
	 * Deletes the given image. This method will also delete a matching
	 * temporary image.
	 *
	 * @see #deleteTemporaryImage(TemporaryImage)
	 * @param image
	 *            The image to delete
	 */
	public void deleteImage(Image image) {
		Validation.begin().isNotNull("Image", image).check().is("Local Sone", isLocalSone(image.getSone())).check();
		deleteTemporaryImage(image.getId());
		image.getAlbum().removeImage(image);
		synchronized (images) {
			images.remove(image.getId());
		}
		touchConfiguration();
	}

	/**
	 * Creates a new temporary image.
	 *
	 * @param mimeType
	 *            The MIME type of the temporary image
	 * @param imageData
	 *            The encoded data of the image
	 * @return The temporary image
	 */
	public TemporaryImage createTemporaryImage(String mimeType, byte[] imageData) {
		TemporaryImage temporaryImage = new TemporaryImage();
		temporaryImage.setMimeType(mimeType).setImageData(imageData);
		synchronized (temporaryImages) {
			temporaryImages.put(temporaryImage.getId(), temporaryImage);
		}
		return temporaryImage;
	}

	/**
	 * Deletes the given temporary image.
	 *
	 * @param temporaryImage
	 *            The temporary image to delete
	 */
	public void deleteTemporaryImage(TemporaryImage temporaryImage) {
		Validation.begin().isNotNull("Temporary Image", temporaryImage).check();
		deleteTemporaryImage(temporaryImage.getId());
	}

	/**
	 * Deletes the temporary image with the given ID.
	 *
	 * @param imageId
	 *            The ID of the temporary image to delete
	 */
	public void deleteTemporaryImage(String imageId) {
		Validation.begin().isNotNull("Temporary Image ID", imageId).check();
		synchronized (temporaryImages) {
			temporaryImages.remove(imageId);
		}
		Image image = getImage(imageId, false);
		if (image != null) {
			imageInserter.cancelImageInsert(image);
		}
	}

	/**
	 * Notifies the core that the configuration, either of the core or of a
	 * single local Sone, has changed, and that the configuration should be
	 * saved.
	 */
	public void touchConfiguration() {
		lastConfigurationUpdate = System.currentTimeMillis();
	}

	//
	// SERVICE METHODS
	//

	/**
	 * Starts the core.
	 */
	@Override
	public void serviceStart() {
		loadConfiguration();
		updateChecker.addUpdateListener(this);
		updateChecker.start();
		webOfTrustUpdater.start();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void serviceRun() {
		long lastSaved = System.currentTimeMillis();
		while (!shouldStop()) {
			sleep(1000);
			long now = System.currentTimeMillis();
			if (shouldStop() || ((lastConfigurationUpdate > lastSaved) && ((now - lastConfigurationUpdate) > 5000))) {
				for (Sone localSone : getLocalSones()) {
					saveSone(localSone);
				}
				saveConfiguration();
				lastSaved = now;
			}
		}
	}

	/**
	 * Stops the core.
	 */
	@Override
	public void serviceStop() {
		synchronized (localSones) {
			for (Entry<Sone, SoneInserter> soneInserter : soneInserters.entrySet()) {
				soneInserter.getValue().removeSoneInsertListener(this);
				soneInserter.getValue().stop();
				saveSone(soneInserter.getKey());
			}
		}
		saveConfiguration();
		webOfTrustUpdater.stop();
		updateChecker.stop();
		updateChecker.removeUpdateListener(this);
		soneDownloader.stop();
	}

	//
	// PRIVATE METHODS
	//

	/**
	 * Saves the given Sone. This will persist all local settings for the given
	 * Sone, such as the friends list and similar, private options.
	 *
	 * @param sone
	 *            The Sone to save
	 */
	private synchronized void saveSone(Sone sone) {
		if (!isLocalSone(sone)) {
			logger.log(Level.FINE, String.format("Tried to save non-local Sone: %s", sone));
			return;
		}
		if (!(sone.getIdentity() instanceof OwnIdentity)) {
			logger.log(Level.WARNING, String.format("Local Sone without OwnIdentity found, refusing to save: %s", sone));
			return;
		}

		logger.log(Level.INFO, String.format("Saving Sone: %s", sone));
		try {
			/* save Sone into configuration. */
			String sonePrefix = "Sone/" + sone.getId();
			configuration.getLongValue(sonePrefix + "/Time").setValue(sone.getTime());
			configuration.getStringValue(sonePrefix + "/LastInsertFingerprint").setValue(soneInserters.get(sone).getLastInsertFingerprint());

			/* save profile. */
			Profile profile = sone.getProfile();
			configuration.getStringValue(sonePrefix + "/Profile/FirstName").setValue(profile.getFirstName());
			configuration.getStringValue(sonePrefix + "/Profile/MiddleName").setValue(profile.getMiddleName());
			configuration.getStringValue(sonePrefix + "/Profile/LastName").setValue(profile.getLastName());
			configuration.getIntValue(sonePrefix + "/Profile/BirthDay").setValue(profile.getBirthDay());
			configuration.getIntValue(sonePrefix + "/Profile/BirthMonth").setValue(profile.getBirthMonth());
			configuration.getIntValue(sonePrefix + "/Profile/BirthYear").setValue(profile.getBirthYear());
			configuration.getStringValue(sonePrefix + "/Profile/Avatar").setValue(profile.getAvatar());

			/* save profile fields. */
			int fieldCounter = 0;
			for (Field profileField : profile.getFields()) {
				String fieldPrefix = sonePrefix + "/Profile/Fields/" + fieldCounter++;
				configuration.getStringValue(fieldPrefix + "/Name").setValue(profileField.getName());
				configuration.getStringValue(fieldPrefix + "/Value").setValue(profileField.getValue());
			}
			configuration.getStringValue(sonePrefix + "/Profile/Fields/" + fieldCounter + "/Name").setValue(null);

			/* save posts. */
			int postCounter = 0;
			for (Post post : sone.getPosts()) {
				String postPrefix = sonePrefix + "/Posts/" + postCounter++;
				configuration.getStringValue(postPrefix + "/ID").setValue(post.getId());
				configuration.getStringValue(postPrefix + "/Recipient").setValue((post.getRecipient() != null) ? post.getRecipient().getId() : null);
				configuration.getLongValue(postPrefix + "/Time").setValue(post.getTime());
				configuration.getStringValue(postPrefix + "/Text").setValue(post.getText());
			}
			configuration.getStringValue(sonePrefix + "/Posts/" + postCounter + "/ID").setValue(null);

			/* save replies. */
			int replyCounter = 0;
			for (PostReply reply : sone.getReplies()) {
				String replyPrefix = sonePrefix + "/Replies/" + replyCounter++;
				configuration.getStringValue(replyPrefix + "/ID").setValue(reply.getId());
				configuration.getStringValue(replyPrefix + "/Post/ID").setValue(reply.getPost().getId());
				configuration.getLongValue(replyPrefix + "/Time").setValue(reply.getTime());
				configuration.getStringValue(replyPrefix + "/Text").setValue(reply.getText());
			}
			configuration.getStringValue(sonePrefix + "/Replies/" + replyCounter + "/ID").setValue(null);

			/* save post likes. */
			int postLikeCounter = 0;
			for (String postId : sone.getLikedPostIds()) {
				configuration.getStringValue(sonePrefix + "/Likes/Post/" + postLikeCounter++ + "/ID").setValue(postId);
			}
			configuration.getStringValue(sonePrefix + "/Likes/Post/" + postLikeCounter + "/ID").setValue(null);

			/* save reply likes. */
			int replyLikeCounter = 0;
			for (String replyId : sone.getLikedReplyIds()) {
				configuration.getStringValue(sonePrefix + "/Likes/Reply/" + replyLikeCounter++ + "/ID").setValue(replyId);
			}
			configuration.getStringValue(sonePrefix + "/Likes/Reply/" + replyLikeCounter + "/ID").setValue(null);

			/* save friends. */
			int friendCounter = 0;
			for (String friendId : sone.getFriends()) {
				configuration.getStringValue(sonePrefix + "/Friends/" + friendCounter++ + "/ID").setValue(friendId);
			}
			configuration.getStringValue(sonePrefix + "/Friends/" + friendCounter + "/ID").setValue(null);

			/* save albums. first, collect in a flat structure, top-level first. */
			List<Album> albums = sone.getAllAlbums();

			int albumCounter = 0;
			for (Album album : albums) {
				String albumPrefix = sonePrefix + "/Albums/" + albumCounter++;
				configuration.getStringValue(albumPrefix + "/ID").setValue(album.getId());
				configuration.getStringValue(albumPrefix + "/Title").setValue(album.getTitle());
				configuration.getStringValue(albumPrefix + "/Description").setValue(album.getDescription());
				configuration.getStringValue(albumPrefix + "/Parent").setValue(album.getParent() == null ? null : album.getParent().getId());
				configuration.getStringValue(albumPrefix + "/AlbumImage").setValue(album.getAlbumImage() == null ? null : album.getAlbumImage().getId());
			}
			configuration.getStringValue(sonePrefix + "/Albums/" + albumCounter + "/ID").setValue(null);

			/* save images. */
			int imageCounter = 0;
			for (Album album : albums) {
				for (Image image : album.getImages()) {
					if (!image.isInserted()) {
						continue;
					}
					String imagePrefix = sonePrefix + "/Images/" + imageCounter++;
					configuration.getStringValue(imagePrefix + "/ID").setValue(image.getId());
					configuration.getStringValue(imagePrefix + "/Album").setValue(album.getId());
					configuration.getStringValue(imagePrefix + "/Key").setValue(image.getKey());
					configuration.getStringValue(imagePrefix + "/Title").setValue(image.getTitle());
					configuration.getStringValue(imagePrefix + "/Description").setValue(image.getDescription());
					configuration.getLongValue(imagePrefix + "/CreationTime").setValue(image.getCreationTime());
					configuration.getIntValue(imagePrefix + "/Width").setValue(image.getWidth());
					configuration.getIntValue(imagePrefix + "/Height").setValue(image.getHeight());
				}
			}
			configuration.getStringValue(sonePrefix + "/Images/" + imageCounter + "/ID").setValue(null);

			/* save options. */
			configuration.getBooleanValue(sonePrefix + "/Options/AutoFollow").setValue(sone.getOptions().getBooleanOption("AutoFollow").getReal());
			configuration.getBooleanValue(sonePrefix + "/Options/ShowNotification/NewSones").setValue(sone.getOptions().getBooleanOption("ShowNotification/NewSones").getReal());
			configuration.getBooleanValue(sonePrefix + "/Options/ShowNotification/NewPosts").setValue(sone.getOptions().getBooleanOption("ShowNotification/NewPosts").getReal());
			configuration.getBooleanValue(sonePrefix + "/Options/ShowNotification/NewReplies").setValue(sone.getOptions().getBooleanOption("ShowNotification/NewReplies").getReal());
			configuration.getBooleanValue(sonePrefix + "/Options/EnableSoneInsertNotifications").setValue(sone.getOptions().getBooleanOption("EnableSoneInsertNotifications").getReal());
			configuration.getStringValue(sonePrefix + "/Options/ShowCustomAvatars").setValue(sone.getOptions().<ShowCustomAvatars> getEnumOption("ShowCustomAvatars").get().name());

			configuration.save();

			webOfTrustUpdater.setProperty((OwnIdentity) sone.getIdentity(), "Sone.LatestEdition", String.valueOf(sone.getLatestEdition()));

			logger.log(Level.INFO, String.format("Sone %s saved.", sone));
		} catch (ConfigurationException ce1) {
			logger.log(Level.WARNING, String.format("Could not save Sone: %s", sone), ce1);
		}
	}

	/**
	 * Saves the current options.
	 */
	private void saveConfiguration() {
		synchronized (configuration) {
			if (storingConfiguration) {
				logger.log(Level.FINE, "Already storing configuration…");
				return;
			}
			storingConfiguration = true;
		}

		/* store the options first. */
		try {
			configuration.getIntValue("Option/ConfigurationVersion").setValue(0);
			configuration.getIntValue("Option/InsertionDelay").setValue(options.getIntegerOption("InsertionDelay").getReal());
			configuration.getIntValue("Option/PostsPerPage").setValue(options.getIntegerOption("PostsPerPage").getReal());
			configuration.getIntValue("Option/ImagesPerPage").setValue(options.getIntegerOption("ImagesPerPage").getReal());
			configuration.getIntValue("Option/CharactersPerPost").setValue(options.getIntegerOption("CharactersPerPost").getReal());
			configuration.getIntValue("Option/PostCutOffLength").setValue(options.getIntegerOption("PostCutOffLength").getReal());
			configuration.getBooleanValue("Option/RequireFullAccess").setValue(options.getBooleanOption("RequireFullAccess").getReal());
			configuration.getIntValue("Option/PositiveTrust").setValue(options.getIntegerOption("PositiveTrust").getReal());
			configuration.getIntValue("Option/NegativeTrust").setValue(options.getIntegerOption("NegativeTrust").getReal());
			configuration.getStringValue("Option/TrustComment").setValue(options.getStringOption("TrustComment").getReal());
			configuration.getBooleanValue("Option/ActivateFcpInterface").setValue(options.getBooleanOption("ActivateFcpInterface").getReal());
			configuration.getIntValue("Option/FcpFullAccessRequired").setValue(options.getIntegerOption("FcpFullAccessRequired").getReal());

			/* save known Sones. */
			int soneCounter = 0;
			synchronized (knownSones) {
				for (String knownSoneId : knownSones) {
					configuration.getStringValue("KnownSone/" + soneCounter++ + "/ID").setValue(knownSoneId);
				}
				configuration.getStringValue("KnownSone/" + soneCounter + "/ID").setValue(null);
			}

			/* save Sone following times. */
			soneCounter = 0;
			synchronized (soneFollowingTimes) {
				for (Entry<Sone, Long> soneFollowingTime : soneFollowingTimes.entrySet()) {
					configuration.getStringValue("SoneFollowingTimes/" + soneCounter + "/Sone").setValue(soneFollowingTime.getKey().getId());
					configuration.getLongValue("SoneFollowingTimes/" + soneCounter + "/Time").setValue(soneFollowingTime.getValue());
					++soneCounter;
				}
				configuration.getStringValue("SoneFollowingTimes/" + soneCounter + "/Sone").setValue(null);
			}

			/* save known posts. */
			int postCounter = 0;
			synchronized (knownPosts) {
				for (String knownPostId : knownPosts) {
					configuration.getStringValue("KnownPosts/" + postCounter++ + "/ID").setValue(knownPostId);
				}
				configuration.getStringValue("KnownPosts/" + postCounter + "/ID").setValue(null);
			}

			/* save known replies. */
			int replyCounter = 0;
			synchronized (knownReplies) {
				for (String knownReplyId : knownReplies) {
					configuration.getStringValue("KnownReplies/" + replyCounter++ + "/ID").setValue(knownReplyId);
				}
				configuration.getStringValue("KnownReplies/" + replyCounter + "/ID").setValue(null);
			}

			/* save bookmarked posts. */
			int bookmarkedPostCounter = 0;
			synchronized (bookmarkedPosts) {
				for (String bookmarkedPostId : bookmarkedPosts) {
					configuration.getStringValue("Bookmarks/Post/" + bookmarkedPostCounter++ + "/ID").setValue(bookmarkedPostId);
				}
			}
			configuration.getStringValue("Bookmarks/Post/" + bookmarkedPostCounter++ + "/ID").setValue(null);

			/* now save it. */
			configuration.save();

		} catch (ConfigurationException ce1) {
			logger.log(Level.SEVERE, "Could not store configuration!", ce1);
		} finally {
			synchronized (configuration) {
				storingConfiguration = false;
			}
		}
	}

	/**
	 * Loads the configuration.
	 */
	@SuppressWarnings("unchecked")
	private void loadConfiguration() {
		/* create options. */
		options.addIntegerOption("InsertionDelay", new DefaultOption<Integer>(60, new IntegerRangeValidator(0, Integer.MAX_VALUE), new OptionWatcher<Integer>() {

			@Override
			public void optionChanged(Option<Integer> option, Integer oldValue, Integer newValue) {
				SoneInserter.setInsertionDelay(newValue);
			}

		}));
		options.addIntegerOption("PostsPerPage", new DefaultOption<Integer>(10, new IntegerRangeValidator(1, Integer.MAX_VALUE)));
		options.addIntegerOption("ImagesPerPage", new DefaultOption<Integer>(9, new IntegerRangeValidator(1, Integer.MAX_VALUE)));
		options.addIntegerOption("CharactersPerPost", new DefaultOption<Integer>(400, new OrValidator<Integer>(new IntegerRangeValidator(50, Integer.MAX_VALUE), new EqualityValidator<Integer>(-1))));
		options.addIntegerOption("PostCutOffLength", new DefaultOption<Integer>(200, new OrValidator<Integer>(new IntegerRangeValidator(50, Integer.MAX_VALUE), new EqualityValidator<Integer>(-1))));
		options.addBooleanOption("RequireFullAccess", new DefaultOption<Boolean>(false));
		options.addIntegerOption("PositiveTrust", new DefaultOption<Integer>(75, new IntegerRangeValidator(0, 100)));
		options.addIntegerOption("NegativeTrust", new DefaultOption<Integer>(-25, new IntegerRangeValidator(-100, 100)));
		options.addStringOption("TrustComment", new DefaultOption<String>("Set from Sone Web Interface"));
		options.addBooleanOption("ActivateFcpInterface", new DefaultOption<Boolean>(false, new OptionWatcher<Boolean>() {

			@Override
			@SuppressWarnings("synthetic-access")
			public void optionChanged(Option<Boolean> option, Boolean oldValue, Boolean newValue) {
				fcpInterface.setActive(newValue);
			}
		}));
		options.addIntegerOption("FcpFullAccessRequired", new DefaultOption<Integer>(2, new OptionWatcher<Integer>() {

			@Override
			@SuppressWarnings("synthetic-access")
			public void optionChanged(Option<Integer> option, Integer oldValue, Integer newValue) {
				fcpInterface.setFullAccessRequired(FullAccessRequired.values()[newValue]);
			}

		}));

		loadConfigurationValue("InsertionDelay");
		loadConfigurationValue("PostsPerPage");
		loadConfigurationValue("ImagesPerPage");
		loadConfigurationValue("CharactersPerPost");
		loadConfigurationValue("PostCutOffLength");
		options.getBooleanOption("RequireFullAccess").set(configuration.getBooleanValue("Option/RequireFullAccess").getValue(null));
		loadConfigurationValue("PositiveTrust");
		loadConfigurationValue("NegativeTrust");
		options.getStringOption("TrustComment").set(configuration.getStringValue("Option/TrustComment").getValue(null));
		options.getBooleanOption("ActivateFcpInterface").set(configuration.getBooleanValue("Option/ActivateFcpInterface").getValue(null));
		options.getIntegerOption("FcpFullAccessRequired").set(configuration.getIntValue("Option/FcpFullAccessRequired").getValue(null));

		/* load known Sones. */
		int soneCounter = 0;
		while (true) {
			String knownSoneId = configuration.getStringValue("KnownSone/" + soneCounter++ + "/ID").getValue(null);
			if (knownSoneId == null) {
				break;
			}
			synchronized (knownSones) {
				knownSones.add(knownSoneId);
			}
		}

		/* load Sone following times. */
		soneCounter = 0;
		while (true) {
			String soneId = configuration.getStringValue("SoneFollowingTimes/" + soneCounter + "/Sone").getValue(null);
			if (soneId == null) {
				break;
			}
			long time = configuration.getLongValue("SoneFollowingTimes/" + soneCounter + "/Time").getValue(Long.MAX_VALUE);
			Sone followedSone = getSone(soneId);
			if (followedSone == null) {
				logger.log(Level.WARNING, String.format("Ignoring Sone with invalid ID: %s", soneId));
			} else {
				synchronized (soneFollowingTimes) {
					soneFollowingTimes.put(getSone(soneId), time);
				}
			}
			++soneCounter;
		}

		/* load known posts. */
		int postCounter = 0;
		while (true) {
			String knownPostId = configuration.getStringValue("KnownPosts/" + postCounter++ + "/ID").getValue(null);
			if (knownPostId == null) {
				break;
			}
			synchronized (knownPosts) {
				knownPosts.add(knownPostId);
			}
		}

		/* load known replies. */
		int replyCounter = 0;
		while (true) {
			String knownReplyId = configuration.getStringValue("KnownReplies/" + replyCounter++ + "/ID").getValue(null);
			if (knownReplyId == null) {
				break;
			}
			synchronized (knownReplies) {
				knownReplies.add(knownReplyId);
			}
		}

		/* load bookmarked posts. */
		int bookmarkedPostCounter = 0;
		while (true) {
			String bookmarkedPostId = configuration.getStringValue("Bookmarks/Post/" + bookmarkedPostCounter++ + "/ID").getValue(null);
			if (bookmarkedPostId == null) {
				break;
			}
			synchronized (bookmarkedPosts) {
				bookmarkedPosts.add(bookmarkedPostId);
			}
		}

	}

	/**
	 * Loads an {@link Integer} configuration value for the option with the
	 * given name, logging validation failures.
	 *
	 * @param optionName
	 *            The name of the option to load
	 */
	private void loadConfigurationValue(String optionName) {
		try {
			options.getIntegerOption(optionName).set(configuration.getIntValue("Option/" + optionName).getValue(null));
		} catch (IllegalArgumentException iae1) {
			logger.log(Level.WARNING, String.format("Invalid value for %s in configuration, using default.", optionName));
		}
	}

	/**
	 * Generate a Sone URI from the given URI and latest edition.
	 *
	 * @param uriString
	 *            The URI to derive the Sone URI from
	 * @return The derived URI
	 */
	private static FreenetURI getSoneUri(String uriString) {
		try {
			FreenetURI uri = new FreenetURI(uriString).setDocName("Sone").setMetaString(new String[0]);
			return uri;
		} catch (MalformedURLException mue1) {
			logger.log(Level.WARNING, String.format("Could not create Sone URI from URI: %s", uriString), mue1);
			return null;
		}
	}

	//
	// INTERFACE IdentityListener
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void ownIdentityAdded(OwnIdentity ownIdentity) {
		logger.log(Level.FINEST, String.format("Adding OwnIdentity: %s", ownIdentity));
		if (ownIdentity.hasContext("Sone")) {
			trustedIdentities.put(ownIdentity, Collections.synchronizedSet(new HashSet<Identity>()));
			addLocalSone(ownIdentity);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void ownIdentityRemoved(OwnIdentity ownIdentity) {
		logger.log(Level.FINEST, String.format("Removing OwnIdentity: %s", ownIdentity));
		trustedIdentities.remove(ownIdentity);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void identityAdded(OwnIdentity ownIdentity, Identity identity) {
		logger.log(Level.FINEST, String.format("Adding Identity: %s", identity));
		trustedIdentities.get(ownIdentity).add(identity);
		addRemoteSone(identity);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void identityUpdated(OwnIdentity ownIdentity, final Identity identity) {
		new Thread(new Runnable() {

			@Override
			@SuppressWarnings("synthetic-access")
			public void run() {
				Sone sone = getRemoteSone(identity.getId(), false);
				sone.setIdentity(identity);
				sone.setLatestEdition(Numbers.safeParseLong(identity.getProperty("Sone.LatestEdition"), sone.getLatestEdition()));
				soneDownloader.addSone(sone);
				soneDownloader.fetchSone(sone);
			}
		}).start();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void identityRemoved(OwnIdentity ownIdentity, Identity identity) {
		trustedIdentities.get(ownIdentity).remove(identity);
		boolean foundIdentity = false;
		for (Entry<OwnIdentity, Set<Identity>> trustedIdentity : trustedIdentities.entrySet()) {
			if (trustedIdentity.getKey().equals(ownIdentity)) {
				continue;
			}
			if (trustedIdentity.getValue().contains(identity)) {
				foundIdentity = true;
			}
		}
		if (foundIdentity) {
			/* some local identity still trusts this identity, don’t remove. */
			return;
		}
		Sone sone = getSone(identity.getId(), false);
		if (sone == null) {
			/* TODO - we don’t have the Sone anymore. should this happen? */
			return;
		}
		synchronized (posts) {
			synchronized (knownPosts) {
				for (Post post : sone.getPosts()) {
					posts.remove(post.getId());
					coreListenerManager.firePostRemoved(post);
				}
			}
		}
		synchronized (replies) {
			synchronized (knownReplies) {
				for (PostReply reply : sone.getReplies()) {
					replies.remove(reply.getId());
					coreListenerManager.fireReplyRemoved(reply);
				}
			}
		}
		synchronized (remoteSones) {
			remoteSones.remove(identity.getId());
		}
		coreListenerManager.fireSoneRemoved(sone);
	}

	//
	// INTERFACE UpdateListener
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void updateFound(Version version, long releaseTime, long latestEdition) {
		coreListenerManager.fireUpdateFound(version, releaseTime, latestEdition);
	}

	//
	// INTERFACE ImageInsertListener
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void insertStarted(Sone sone) {
		coreListenerManager.fireSoneInserting(sone);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void insertFinished(Sone sone, long insertDuration) {
		coreListenerManager.fireSoneInserted(sone, insertDuration);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void insertAborted(Sone sone, Throwable cause) {
		coreListenerManager.fireSoneInsertAborted(sone, cause);
	}

	//
	// SONEINSERTLISTENER METHODS
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void imageInsertStarted(Image image) {
		logger.log(Level.WARNING, String.format("Image insert started for %s...", image));
		coreListenerManager.fireImageInsertStarted(image);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void imageInsertAborted(Image image) {
		logger.log(Level.WARNING, String.format("Image insert aborted for %s.", image));
		coreListenerManager.fireImageInsertAborted(image);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void imageInsertFinished(Image image, FreenetURI key) {
		logger.log(Level.WARNING, String.format("Image insert finished for %s: %s", image, key));
		image.setKey(key.toString());
		deleteTemporaryImage(image.getId());
		touchConfiguration();
		coreListenerManager.fireImageInsertFinished(image);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void imageInsertFailed(Image image, Throwable cause) {
		logger.log(Level.WARNING, String.format("Image insert failed for %s." + image), cause);
		coreListenerManager.fireImageInsertFailed(image, cause);
	}

	/**
	 * Convenience interface for external classes that want to access the core’s
	 * configuration.
	 *
	 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
	 */
	public static class Preferences {

		/** The wrapped options. */
		private final Options options;

		/**
		 * Creates a new preferences object wrapped around the given options.
		 *
		 * @param options
		 *            The options to wrap
		 */
		public Preferences(Options options) {
			this.options = options;
		}

		/**
		 * Returns the insertion delay.
		 *
		 * @return The insertion delay
		 */
		public int getInsertionDelay() {
			return options.getIntegerOption("InsertionDelay").get();
		}

		/**
		 * Validates the given insertion delay.
		 *
		 * @param insertionDelay
		 *            The insertion delay to validate
		 * @return {@code true} if the given insertion delay was valid,
		 *         {@code false} otherwise
		 */
		public boolean validateInsertionDelay(Integer insertionDelay) {
			return options.getIntegerOption("InsertionDelay").validate(insertionDelay);
		}

		/**
		 * Sets the insertion delay
		 *
		 * @param insertionDelay
		 *            The new insertion delay, or {@code null} to restore it to
		 *            the default value
		 * @return This preferences
		 */
		public Preferences setInsertionDelay(Integer insertionDelay) {
			options.getIntegerOption("InsertionDelay").set(insertionDelay);
			return this;
		}

		/**
		 * Returns the number of posts to show per page.
		 *
		 * @return The number of posts to show per page
		 */
		public int getPostsPerPage() {
			return options.getIntegerOption("PostsPerPage").get();
		}

		/**
		 * Validates the number of posts per page.
		 *
		 * @param postsPerPage
		 *            The number of posts per page
		 * @return {@code true} if the number of posts per page was valid,
		 *         {@code false} otherwise
		 */
		public boolean validatePostsPerPage(Integer postsPerPage) {
			return options.getIntegerOption("PostsPerPage").validate(postsPerPage);
		}

		/**
		 * Sets the number of posts to show per page.
		 *
		 * @param postsPerPage
		 *            The number of posts to show per page
		 * @return This preferences object
		 */
		public Preferences setPostsPerPage(Integer postsPerPage) {
			options.getIntegerOption("PostsPerPage").set(postsPerPage);
			return this;
		}

		/**
		 * Returns the number of images to show per page.
		 *
		 * @return The number of images to show per page
		 */
		public int getImagesPerPage() {
			return options.getIntegerOption("ImagesPerPage").get();
		}

		/**
		 * Validates the number of images per page.
		 *
		 * @param imagesPerPage
		 *            The number of images per page
		 * @return {@code true} if the number of images per page was valid,
		 *         {@code false} otherwise
		 */
		public boolean validateImagesPerPage(Integer imagesPerPage) {
			return options.getIntegerOption("ImagesPerPage").validate(imagesPerPage);
		}

		/**
		 * Sets the number of images per page.
		 *
		 * @param imagesPerPage
		 *            The number of images per page
		 * @return This preferences object
		 */
		public Preferences setImagesPerPage(Integer imagesPerPage) {
			options.getIntegerOption("ImagesPerPage").set(imagesPerPage);
			return this;
		}

		/**
		 * Returns the number of characters per post, or <code>-1</code> if the
		 * posts should not be cut off.
		 *
		 * @return The numbers of characters per post
		 */
		public int getCharactersPerPost() {
			return options.getIntegerOption("CharactersPerPost").get();
		}

		/**
		 * Validates the number of characters per post.
		 *
		 * @param charactersPerPost
		 *            The number of characters per post
		 * @return {@code true} if the number of characters per post was valid,
		 *         {@code false} otherwise
		 */
		public boolean validateCharactersPerPost(Integer charactersPerPost) {
			return options.getIntegerOption("CharactersPerPost").validate(charactersPerPost);
		}

		/**
		 * Sets the number of characters per post.
		 *
		 * @param charactersPerPost
		 *            The number of characters per post, or <code>-1</code> to
		 *            not cut off the posts
		 * @return This preferences objects
		 */
		public Preferences setCharactersPerPost(Integer charactersPerPost) {
			options.getIntegerOption("CharactersPerPost").set(charactersPerPost);
			return this;
		}

		/**
		 * Returns the number of characters the shortened post should have.
		 *
		 * @return The number of characters of the snippet
		 */
		public int getPostCutOffLength() {
			return options.getIntegerOption("PostCutOffLength").get();
		}

		/**
		 * Validates the number of characters after which to cut off the post.
		 *
		 * @param postCutOffLength
		 *            The number of characters of the snippet
		 * @return {@code true} if the number of characters of the snippet is
		 *         valid, {@code false} otherwise
		 */
		public boolean validatePostCutOffLength(Integer postCutOffLength) {
			return options.getIntegerOption("PostCutOffLength").validate(postCutOffLength);
		}

		/**
		 * Sets the number of characters the shortened post should have.
		 *
		 * @param postCutOffLength
		 *            The number of characters of the snippet
		 * @return This preferences
		 */
		public Preferences setPostCutOffLength(Integer postCutOffLength) {
			options.getIntegerOption("PostCutOffLength").set(postCutOffLength);
			return this;
		}

		/**
		 * Returns whether Sone requires full access to be even visible.
		 *
		 * @return {@code true} if Sone requires full access, {@code false}
		 *         otherwise
		 */
		public boolean isRequireFullAccess() {
			return options.getBooleanOption("RequireFullAccess").get();
		}

		/**
		 * Sets whether Sone requires full access to be even visible.
		 *
		 * @param requireFullAccess
		 *            {@code true} if Sone requires full access, {@code false}
		 *            otherwise
		 */
		public void setRequireFullAccess(Boolean requireFullAccess) {
			options.getBooleanOption("RequireFullAccess").set(requireFullAccess);
		}

		/**
		 * Returns the positive trust.
		 *
		 * @return The positive trust
		 */
		public int getPositiveTrust() {
			return options.getIntegerOption("PositiveTrust").get();
		}

		/**
		 * Validates the positive trust.
		 *
		 * @param positiveTrust
		 *            The positive trust to validate
		 * @return {@code true} if the positive trust was valid, {@code false}
		 *         otherwise
		 */
		public boolean validatePositiveTrust(Integer positiveTrust) {
			return options.getIntegerOption("PositiveTrust").validate(positiveTrust);
		}

		/**
		 * Sets the positive trust.
		 *
		 * @param positiveTrust
		 *            The new positive trust, or {@code null} to restore it to
		 *            the default vlaue
		 * @return This preferences
		 */
		public Preferences setPositiveTrust(Integer positiveTrust) {
			options.getIntegerOption("PositiveTrust").set(positiveTrust);
			return this;
		}

		/**
		 * Returns the negative trust.
		 *
		 * @return The negative trust
		 */
		public int getNegativeTrust() {
			return options.getIntegerOption("NegativeTrust").get();
		}

		/**
		 * Validates the negative trust.
		 *
		 * @param negativeTrust
		 *            The negative trust to validate
		 * @return {@code true} if the negative trust was valid, {@code false}
		 *         otherwise
		 */
		public boolean validateNegativeTrust(Integer negativeTrust) {
			return options.getIntegerOption("NegativeTrust").validate(negativeTrust);
		}

		/**
		 * Sets the negative trust.
		 *
		 * @param negativeTrust
		 *            The negative trust, or {@code null} to restore it to the
		 *            default value
		 * @return The preferences
		 */
		public Preferences setNegativeTrust(Integer negativeTrust) {
			options.getIntegerOption("NegativeTrust").set(negativeTrust);
			return this;
		}

		/**
		 * Returns the trust comment. This is the comment that is set in the web
		 * of trust when a trust value is assigned to an identity.
		 *
		 * @return The trust comment
		 */
		public String getTrustComment() {
			return options.getStringOption("TrustComment").get();
		}

		/**
		 * Sets the trust comment.
		 *
		 * @param trustComment
		 *            The trust comment, or {@code null} to restore it to the
		 *            default value
		 * @return This preferences
		 */
		public Preferences setTrustComment(String trustComment) {
			options.getStringOption("TrustComment").set(trustComment);
			return this;
		}

		/**
		 * Returns whether the {@link FcpInterface FCP interface} is currently
		 * active.
		 *
		 * @see FcpInterface#setActive(boolean)
		 * @return {@code true} if the FCP interface is currently active,
		 *         {@code false} otherwise
		 */
		public boolean isFcpInterfaceActive() {
			return options.getBooleanOption("ActivateFcpInterface").get();
		}

		/**
		 * Sets whether the {@link FcpInterface FCP interface} is currently
		 * active.
		 *
		 * @see FcpInterface#setActive(boolean)
		 * @param fcpInterfaceActive
		 *            {@code true} to activate the FCP interface, {@code false}
		 *            to deactivate the FCP interface
		 * @return This preferences object
		 */
		public Preferences setFcpInterfaceActive(boolean fcpInterfaceActive) {
			options.getBooleanOption("ActivateFcpInterface").set(fcpInterfaceActive);
			return this;
		}

		/**
		 * Returns the action level for which full access to the FCP interface
		 * is required.
		 *
		 * @return The action level for which full access to the FCP interface
		 *         is required
		 */
		public FullAccessRequired getFcpFullAccessRequired() {
			return FullAccessRequired.values()[options.getIntegerOption("FcpFullAccessRequired").get()];
		}

		/**
		 * Sets the action level for which full access to the FCP interface is
		 * required
		 *
		 * @param fcpFullAccessRequired
		 *            The action level
		 * @return This preferences
		 */
		public Preferences setFcpFullAccessRequired(FullAccessRequired fcpFullAccessRequired) {
			options.getIntegerOption("FcpFullAccessRequired").set((fcpFullAccessRequired != null) ? fcpFullAccessRequired.ordinal() : null);
			return this;
		}

	}

}
