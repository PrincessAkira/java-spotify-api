package de.labystudio.spotifyapi.platform.windows.api.spotify;

import de.labystudio.spotifyapi.platform.windows.api.WinProcess;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import de.labystudio.spotifyapi.platform.windows.api.playback.MemoryPlaybackAccessor;
import de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor;
import de.labystudio.spotifyapi.platform.windows.api.playback.PseudoPlaybackAccessor;

/**
 * This class represents the Spotify Windows application.
 *
 * @author LabyStudio
 */
public class SpotifyProcess extends WinProcess {

    private static final boolean DEBUG = System.getProperty("SPOTIFY_API_DEBUG") != null;

    // Spotify track id
    private static final String PREFIX_SPOTIFY_TRACK = "spotify:track:";
    private static final long[] OFFSETS_TRACK_ID = {
            0x14FA30, // 64-Bit (1.2.21.1104.g42cf0a50)
            0x106198, // 32-Bit (1.2.21.1104.g42cf0a50)
            0x14C9F0, // 64-Bit (Old)
            0x102178, // 32-Bit (Old)
            0x1499F0, // 64-Bit (Old)
            0xFEFE8 // 32-Bit (Old)
    };

    private final long addressTrackId;
    private final PlaybackAccessor playbackAccessor;

    private SpotifyTitle previousTitle = SpotifyTitle.UNKNOWN;

    /**
     * Creates a new instance of the {@link SpotifyProcess} class.
     * It will immediately try to connect to the Spotify application.
     *
     * @throws IllegalStateException if the Spotify process could not be found.
     */
    public SpotifyProcess() {
        super("Spotify.exe");

        if (DEBUG) {
            System.out.println("Spotify process loaded! Searching for addresses...");
        }

        long timeScanStart = System.currentTimeMillis();
        this.addressTrackId = this.findTrackIdAddress();
        this.playbackAccessor = this.findPlaybackAccessor();

        if (DEBUG) {
            System.out.println("Scanning took " + (System.currentTimeMillis() - timeScanStart) + "ms");
        }
    }

    private long findTrackIdAddress() {
        Psapi.ModuleInfo chromeElfModule = this.getModuleInfo("chrome_elf.dll");
        if (chromeElfModule == null) {
            throw new IllegalStateException("Could not find chrome_elf.dll module");
        }

        // Find address of track id (Located in the chrome_elf.dll module)
        long chromeElfAddress = chromeElfModule.getBaseOfDll();

        // Check all offsets for valid track id
        long addressTrackId = -1;
        long minTrackIdOffset = Long.MAX_VALUE;
        long maxTrackIdOffset = Long.MIN_VALUE;
        for (long trackIdOffset : OFFSETS_TRACK_ID) {
            // Get min and max of hardcoded offset
            minTrackIdOffset = Math.min(minTrackIdOffset, trackIdOffset);
            maxTrackIdOffset = Math.max(maxTrackIdOffset, trackIdOffset);

            // Check if the hardcoded offset is valid
            long targetAddressTrackId = chromeElfAddress + trackIdOffset;
            if (this.isTrackIdValid(this.readTrackId(targetAddressTrackId))) {
                // If the offset works, exit the loop
                addressTrackId = targetAddressTrackId;
                break;
            }
        }

        // If the hardcoded offsets are not valid, try to find it dynamically
        if (addressTrackId == -1) {
            if (DEBUG) {
                System.out.println("Could not find track id with hardcoded offsets. Trying to find it dynamically...");
            }

            long threshold = (maxTrackIdOffset - minTrackIdOffset) * 3;
            long scanAddressFrom = chromeElfAddress + minTrackIdOffset - threshold;
            long scanAddressTo = chromeElfAddress + maxTrackIdOffset + threshold;
            addressTrackId = this.findAddressOfText(scanAddressFrom, scanAddressTo, "spotify:track:", (address, index) -> {
                return this.isTrackIdValid(this.readTrackId(address));
            });
        }

        if (addressTrackId == -1) {
            throw new IllegalStateException("Could not find track id in memory");
        }

        if (DEBUG) {
            System.out.printf(
                    "Found track id address: %s (+%s) [%s%s]%n",
                    Long.toHexString(addressTrackId),
                    Long.toHexString(addressTrackId - chromeElfAddress),
                    PREFIX_SPOTIFY_TRACK,
                    this.readTrackId(addressTrackId)
            );
        }
        return addressTrackId;
    }

    private PlaybackAccessor findPlaybackAccessor() {
        // Find addresses of playback states when playing a playlist
        long addressPlayBack = this.findAddressOfText(0, 0x0FFFFFFF, "playlist", (address, index) -> {
            return this.hasText(address + 408, "context", "autoplay")
                    && this.hasText(address + 128, "your_library", "home")
                    && new MemoryPlaybackAccessor(this, address).isValid();
        });

        if (addressPlayBack == -1) {
            // Find addresses of playback states when playing an album
            addressPlayBack = this.findAddressOfText(0, 0x0FFFFFFF, "album", (address, index) -> {
                return this.hasText(address + 408, "context", "autoplay")
                        && this.hasText(address + 128, "your_library", "home")
                        && new MemoryPlaybackAccessor(this, address).isValid();
            });
        }

        if (addressPlayBack == -1) {
            if (DEBUG) {
                System.out.println("Could not find playback address in memory");
            }
            return new PseudoPlaybackAccessor(this);
        }

        // Create the playback accessor with the found address
        MemoryPlaybackAccessor playbackAccessor = new MemoryPlaybackAccessor(this, addressPlayBack);
        if (!playbackAccessor.isValid()) {
            if (DEBUG) {
                System.out.println("Found playback address is not valid");
            }
            return new PseudoPlaybackAccessor(this);
        }

        if (DEBUG) {
            System.out.println("Found playback address at: " + Long.toHexString(addressPlayBack));
        }

        return playbackAccessor;
    }

    /**
     * Read the track id from the memory.
     *
     * @param address The address where the prefix "spotify:track:" starts
     * @return the track id without the prefix "spotify:track:"
     */
    private String readTrackId(long address) {
        return this.readString(address + 14, 22);
    }

    /**
     * Read the track id from the memory.
     *
     * @return the track id without the prefix "spotify:track:"
     */
    public String getTrackId() {
        return this.readTrackId(this.addressTrackId);
    }

    /**
     * Read the playback state from the title bar.
     * <p>
     * If the title bar contains the delimiter " - ", the song is playing.
     *
     * @return true if the song is playing, false if the song is paused
     */
    public boolean isPlayingUsingTitle() {
        return this.getWindowTitle().contains(SpotifyTitle.DELIMITER);
    }

    /**
     * Read the currently playing track name and artist from the title bar.
     * If no song is playing it will return a cached value.
     *
     * @return the currently playing track name and artist
     */
    public SpotifyTitle getTitle() {
        SpotifyTitle title = SpotifyTitle.of(this.getWindowTitle());
        if (title == null) {
            return this.previousTitle;
        }
        return (this.previousTitle = title);
    }

    public PlaybackAccessor getPlaybackAccessor() {
        return this.playbackAccessor;
    }

    public long getAddressTrackId() {
        return this.addressTrackId;
    }

    /**
     * Checks if the given track ID is valid.
     * A track ID is valid if there are no characters with a value of zero.
     *
     * @param trackId The track ID to check.
     * @return True if the track ID is valid, false otherwise.
     */
    public boolean isTrackIdValid(String trackId) {
        for (char c : trackId.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }
}