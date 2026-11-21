/*
 * Timeout and retry policy for the connection layer, in one place.
 *
 * Every value here has a documented "fast link" column that is byte-identical
 * to the constants this build shipped before the policy existed, so a device on
 * WiFi or LTE behaves exactly as it always did. Only the slow profiles differ.
 *
 * The distinction that matters most is handshake vs established. Previously
 * both used the same 4 s timer, which meant a link slow enough to need a longer
 * handshake could never finish one: the timer fired, the handshake was thrown
 * away and restarted, and the budget reset. A handshake happens once per
 * connection and deserves the generous budget; an established connection that
 * goes quiet is a different signal and keeps the short one.
 */

#ifndef NETPOLICY_H
#define NETPOLICY_H

#include "Defines.h"

class NetPolicy {

public:

    // ---- handshake phase -------------------------------------------------
    // The timer while the MTProto handshake is still in flight. Fast keeps the
    // historical 4 s; a 2G RTT can exceed a second on its own and the modern
    // handshake is several round trips, so it needs an order of magnitude more.
    static uint32_t handshakeTimeout(int32_t profile) {
        switch (profile) {
            case NETWORK_PROFILE_2G: return 60;
            case NETWORK_PROFILE_3G: return 30;
            default:                 return 4;
        }
    }

    // ---- TCP connect phase -----------------------------------------------
    // Before any byte has arrived. Historically 8 s when the next port is being
    // tried and 12 s otherwise. SYN + TLS on 2G routinely exceeds both.
    static uint32_t connectTimeout(int32_t profile, bool tryingNextPort) {
        switch (profile) {
            case NETWORK_PROFILE_2G: return tryingNextPort ? 30u : 45u;
            case NETWORK_PROFILE_3G: return tryingNextPort ? 15u : 22u;
            default:                 return tryingNextPort ? 8u : 12u;
        }
    }

    // ---- established connection ------------------------------------------
    // An established connection going quiet keeps the short budget on every
    // profile: a socket that has already delivered data and then stalls is
    // dead, not slow, and waiting 60 s for it only delays recovery.
    static uint32_t establishedTimeout(int32_t profile, uint32_t attempt) {
        // The caller grows `attempt` per failed cycle; on slow links the growth
        // is what lets a handshake eventually complete instead of resetting the
        // budget every time. Cap stays modest so a genuinely dead link is still
        // noticed within a reasonable window.
        uint32_t base = profile == NETWORK_PROFILE_FAST || profile == NETWORK_PROFILE_UNKNOWN ? 4u
                      : profile == NETWORK_PROFILE_3G ? 8u : 15u;
        uint32_t cap  = profile == NETWORK_PROFILE_FAST || profile == NETWORK_PROFILE_UNKNOWN ? 16u
                      : profile == NETWORK_PROFILE_3G ? 30u : 45u;
        uint32_t timeout = base;
        for (uint32_t i = 1; i < attempt && timeout < cap; i++) {
            timeout *= 2;
        }
        return timeout > cap ? cap : timeout;
    }

    // ---- reconnect delay -------------------------------------------------
    // Historically a flat 1 s. Exponential with headroom, plus jitter, so that
    // every device on an operator that just dropped does not come back in
    // lockstep and stampede the server into another outage.
    static uint32_t reconnectDelay(int32_t profile, uint32_t attempt, uint32_t jitterPercent) {
        uint32_t base = 1000;
        uint32_t cap  = profile == NETWORK_PROFILE_FAST || profile == NETWORK_PROFILE_UNKNOWN ? 15000u
                      : profile == NETWORK_PROFILE_3G ? 30000u : 60000u;
        uint32_t delay = base;
        for (uint32_t i = 1; i < attempt && delay < cap; i++) {
            delay *= 2;
        }
        if (delay > cap) {
            delay = cap;
        }
        if (jitterPercent > 0) {
            if (jitterPercent > 50) {
                jitterPercent = 50;
            }
            // Widen downward only: `delay` stays a ceiling, so jitter can never
            // make a retry more aggressive than the policy intends.
            uint32_t span = delay * jitterPercent / 100;
            delay -= span / 2;
        }
        return delay;
    }

    // How many consecutive failed cycles before the caller stops backing off
    // linearly and treats the link as unusable (triggering address re-discovery
    // rather than more blind retries).
    static uint32_t exhaustedAttempts(int32_t profile) {
        return profile == NETWORK_PROFILE_2G ? 6u : profile == NETWORK_PROFILE_3G ? 5u : 4u;
    }
};

#endif
