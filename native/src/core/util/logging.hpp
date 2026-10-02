#pragma once

#include <ostream>
#include <string>

namespace radiante {

/**
 * Everything the renderer prints is diagnostic: which pipeline was assembled, what NGX created, how many textures
 * were stitched. Useful while developing and noise in a player's log, so it is off unless asked for. Errors that a
 * player can act on are written through the Java logger instead, which this does not touch.
 */
void setLoggingEnabled(bool enabled);

bool loggingEnabled();

/** std::cout while logging is on, and a stream that discards everything while it is off. */
std::ostream &out();

/** Errors: on stderr while logging is on, and always kept for the game's log (drainErrors). */
std::ostream &err();

/** The error lines written since the last call, one per line. */
std::string drainErrors();

} // namespace radiante
