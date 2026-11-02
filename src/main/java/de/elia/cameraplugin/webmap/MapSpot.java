package de.elia.cameraplugin.webmap;

/**
 * One public spot as a web map shows it: a pin where it is, with a name
 * and, on a click, what it is.
 *
 * @param id     what the map knows the pin by: the owner and the spot, never two the same
 * @param world  the name of the world it lies in
 * @param label  its name on the map, plain text
 * @param detail what a click on it shows, as HTML
 * @param top    whether it is the build of the week, which gets a pin of its own where the map has one
 */
record MapSpot(String id, String world, double x, double y, double z, String label, String detail, boolean top) {
}
