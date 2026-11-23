package de.elia.cameraplugin.tour;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One tour of {@link CamTour}: its guide's guests, and where the guide was
 * last tick. Only {@link CamTour} changes who is on it.
 */
final class Tour {
    final Map<UUID, Guest> guests = new LinkedHashMap<>();
    Location last;
    /** His way, which the cameras of the guests fly two ticks behind him. */
    final TourTrail trail = new TourTrail();
    /** The seconds the warning in the guide's action bar showed last, -1 while it shows none. */
    long shownSeconds = -1L;
    /** Whom that warning named. */
    List<String> shownNames = List.of();
}
