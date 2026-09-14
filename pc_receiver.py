"""
PC RECEIVER for the Atom Matrix optical link. Whole-matrix-one-colour
protocol (see atom_matrix_sender.py / HANDOFF.md) - there is no spatial
structure inside the LED grid to read, so this does not sample a 5x5 grid
or look at corners vs interior. An earlier version of this receiver did
(corner reference, interior delta) - that scheme was abandoned, see
HANDOFF.md section 5, and this docstring used to still describe it after
the code moved on. It doesn't anymore.

    pip install opencv-python numpy
    python pc_receiver.py --list-cameras
    python pc_receiver.py [--camera N]      q to quit

Pipeline, in order (each stage exists because of a specific failure mode
- see HANDOFF.md section 3 for the full story):

  1. FlickerMap   - only pixels that are actually changing become candidates.
  2. Shape filter - aspect ratio + bounding-box fill, to reject cables.
  3. ColorSplitter - learns the transmitter's own two colours from what it
     observes, rather than trusting fixed hue targets a real camera will
     not render accurately. Never decides which colour means 1; both
     polarities are decoded in parallel and the chunk CRC picks the winner.
  4. Tracker      - a persistent lock, not a per-frame decision.
  5. Decoder / Assembler - bit framing, header search, chunk reassembly.
"""

import cv2
import numpy as np
import time
import argparse
import sys

sys.stdout.reconfigure(line_buffering=True)

# ================== must match atom_matrix_sender.py ==================
CHUNK_SIZE = 8

# ================== receiver tunables ==================
DEBUG = False
SAT_MIN_INITIAL = 120    # starting saturation floor for finding the matrix at all. Adapts
                         # at runtime (see below) - this is only the starting guess.
SAT_FLOOR = 32           # never relax the adaptive floor below this. Low, because in
                         # bright daylight a diffused LED gets diluted by ambient light
                         # reflecting off its own diffuser and its saturation drops a lot.
VAL_MIN = 42             # brightness floor, rejects dark saturated junk. Kept low so a
                         # dim magenta band (its blue+red channels are half-lit at best)
                         # still clears it - testing showed 60 cut off ZERO frames first.
MIN_BLOB_AREA = 200      # px^2. The matrix is a solid block of lit LEDs; this is much larger
                         # than before because we now need enough pixels to actually resolve
                         # a 5x5 grid inside it, not just detect "something coloured".
SEARCH_RADIUS = 200      # px around last lock to search once locked
LOST_TIMEOUT_MS = 1500   # widen back to full frame after this long with no valid read
MAX_AREA_FRAC = 0.12     # a candidate bigger than this fraction of the frame is not the
                         # matrix. Without a cap, proximity merging snowballs: each merge
                         # enlarges the box, which widens the merge radius, which swallows
                         # more contours, until one blob covers half the scene.
ACTIVITY_WINDOW_MS = 4000  # a real transmitter alternates SYNC/data continuously. A static
                           # object (logo, sticker, painted square) reads as the SAME state
                           # forever. A solid magenta rectangle reads as a valid SYNC frame
                           # (corners == interior -> delta 0), so structure alone cannot
                           # reject it - only behaviour over time can. Note an earlier
                           # attempt used "does it have visible gaps between LEDs" instead;
                           # that worked until motion blur filled the gaps in and then
                           # rejected the REAL matrix, which is much worse.
ACTIVITY_MIN_STATES = 2    # distinct states needed within that window to trust a location
BLINK_MIN_SWING = 22       # min peak-to-peak variation in corner brightness to count as
                           # "the corners are blinking". The sender modulates them on a
                           # fixed rhythm, so a real transmitter always shows swing here
                           # while anything static shows none. This is an INDEPENDENT
                           # route to trusting a location: it works in the brightness
                           # channel, so it still functions when hue is too degraded to
                           # classify - which is exactly when the state-based test is
                           # useless, since no states can be decoded at all.
BLINK_MIN_SAMPLES = 12     # corner-brightness samples needed before judging swing
VOTE_WINDOW = 5          # frames of history for the run-length debounce
VOTE_MIN = 3
STALE_MS = 8000


def crc8(data):
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ 0x07) & 0xFF if crc & 0x80 else (crc << 1) & 0xFF
    return crc


def bits_to_int(bits):
    v = 0
    for b in bits:
        v = (v << 1) | b
    return v


def bits_to_ascii(bits):
    out = []
    for i in range(0, len(bits) - 7, 8):
        byte = bits_to_int(bits[i:i + 8])
        out.append(chr(byte) if 32 <= byte < 127 else ".")
    return "".join(out)


def circular_mean_hue(hues):
    """Hue wraps at 180 in OpenCV's scale, so a plain average is wrong near
    the wrap point. Average the unit vectors instead."""
    rad = np.deg2rad(np.asarray(hues, dtype=np.float32) * 2.0)
    mean = np.degrees(np.arctan2(np.sin(rad).mean(), np.cos(rad).mean())) % 360
    return mean / 2.0


def circular_spread(hues):
    """How tightly clustered a set of hues is, 0 = identical. Uses the length
    of the mean unit vector, so it is wrap-safe like the mean above."""
    if len(hues) < 2:
        return 0.0
    rad = np.deg2rad(np.asarray(hues, dtype=np.float32) * 2.0)
    r = np.hypot(np.sin(rad).mean(), np.cos(rad).mean())
    r = min(1.0, max(1e-9, r))
    return float(np.degrees(np.sqrt(max(0.0, -2.0 * np.log(r)))) / 2.0)


def hue_distance(a, b):
    """Shortest distance between two hues on the 0-179 wheel."""
    d = abs(a - b)
    return min(d, 180 - d)


def circular_delta(a, b):
    """Signed a-b in OpenCV hue units, result in -90..90."""
    return ((a - b + 90) % 180) - 90


# Two hue targets, 90 degrees apart. Adapted at runtime, but the gap is so wide
# that even a badly shifted white balance cannot confuse one for the other.
HUE_ACCEPT = 45   # BOOTSTRAP window only, used until the colour model has learned what
                  # this camera actually renders. It has to be wide: a real camera was
                  # measured showing green at hue 88 instead of 60, and a tight window
                  # rejected it, so nothing was ever observed and the model could never
                  # learn - a deadlock. 45 still excludes red (60 away) and orange (48),
                  # and once learned the model uses its own, much tighter spacing.
BLOB_MIN_SAT = 85 # LEDs are saturated emitters; this keeps skin and pale fabric out
HUE_ADAPT = 0.02

HUE_ONE_HOME = 60
HUE_ZERO_HOME = 120

FLICKER_HISTORY = 10     # frames of history used to measure how much each pixel changes
FLICKER_MIN = 26         # per-pixel colour change needed to count as "this is flickering"
FLICKER_MIN_AREA = 150   # px of flickering area needed before a region is a candidate
OUTLIER_MIN_MARGIN = 16  # a reading is only an outlier if it is at least this far from
                         # both learned colours. An absolute floor, because a
                         # proportional margin collapses when the colours are close.
SHAPE_ASPECT_MAX = 1.9   # the matrix is square; reject anything much longer than wide
SHAPE_MIN_EXTENT = 0.5   # and it must FILL its bounding box. This is the check that
                         # rejects a diagonal cable, whose bounding box is deceptively
                         # square but which fills only about 16% of it.


class ColorSplitter:
    """Learns to tell the two data colours apart WITHOUT knowing what they are.

    Every previous version anchored to specific hues - green at 60, blue at
    120 - and every version broke, because a real camera does not render them
    there. This one never names a colour. It watches the hues the tracked
    transmitter actually produces, and splits them into two groups at the
    widest gap in the distribution. Whatever the camera, the lighting or the
    white balance, there are two clusters and the split falls between them.

    It also self-checks. Real text never produces a long run of identical
    bits, so if the split starts returning the same symbol many times in a
    row it must be wrong - both colours are landing on one side of it. That
    is the signal to throw the fit away and rebuild it, and it is what makes
    this recover on its own instead of needing a restart.

    Which group means 1 and which means 0 is deliberately not decided here:
    the receiver decodes both ways and lets the chunk CRC pick the winner."""

    MAX_SAMPLES = 140
    MIN_SAMPLES = 20
    # TWO different measurements, and they must not share a constant - conflating
    # them was a real bug. MIN_VOID is the empty space BETWEEN the two clusters;
    # MIN_CENTRE_SEP is the distance between their centres. With centres 20 apart
    # and a few degrees of noise either side, the void is only about 12, so a
    # single threshold of 13 rejected a perfectly good fit - and did so
    # intermittently, depending on how the noise happened to fall.
    MIN_VOID = 5          # empty span separating the two clusters
    MIN_CENTRE_SEP = 12   # distance between the two cluster centres.
                          # Do NOT raise this expecting the theoretical 60 degree
                          # separation: on real hardware the washed-out LEDs were
                          # measured at 84 and 104, only 20 apart, because the
                          # diffuser blends white in and pulls the hues together.
    MAX_RUN = 32          # identical BITS in a row before the fit is disowned.
                          # Was 14, which misfired on perfectly good data: a header
                          # with idx=0 and total=4 is 00000000 00000100 - thirteen
                          # consecutive zeros - so valid packets kept destroying a
                          # correct fit. A genuinely wrong split produces an
                          # unbounded run, so 32 still catches it almost immediately
                          # while leaving real data alone.
    BIT_GAP_MS = 70       # a lit frame this long after the previous one starts a new bit
    LEARN_MARGIN = 10     # once a fit exists, only samples this close to a cluster may
                          # refine it. Absolute, not gap-relative.
    FREEZE_AFTER = 120    # consistent post-fit samples (~4 s) before the fit is frozen

    def __init__(self):
        self.samples = []
        self.centre = None      # rotation reference, handles hue wrap-around
        self.threshold = None
        self.lo_c = None
        self.hi_c = None
        self.run_sym = None
        self.run_len = 0
        self.refits = 0
        self._last_lit_ms = None
        self.frozen = False
        self.stable = 0

    def _rot(self, hue):
        return ((hue - self.centre + 90) % 180) - 90

    def freeze(self):
        """Stop adapting. Called once real data is flowing: the colours cannot
        genuinely change mid-transmission, so any further 'learning' can only be
        drift away from a fit that is demonstrably working."""
        self.frozen = True

    def reset(self):
        self.samples = []
        self.threshold = self.lo_c = self.hi_c = None
        self.run_sym, self.run_len = None, 0
        self.frozen = False
        self.stable = 0

    def observe(self, hue):
        if self.frozen:
            return
        # Once a fit exists, an outlier is not evidence - it is something else that
        # drifted into the tracked box. Letting those become training samples pulled
        # a centre from 60 down to 50 and widened a 60 degree pair to 70.
        if self.ready():
            # Learning margin is TIGHT and absolute, and deliberately narrower than
            # anything used for classifying. The wide margin that used to sit here
            # let a cluster walk: blue read at 152, a reflection at 170 was "close
            # enough", then the cable at 4 was close enough to 170, and within a
            # minute the "blue" cluster sat at hue 4 while real blue frames were
            # being thrown away as outliers. Seen live, twice.
            x = self._rot(hue)
            if min(abs(x - self.lo_c), abs(x - self.hi_c)) > self.LEARN_MARGIN:
                return
            self.stable += 1
            if self.stable >= self.FREEZE_AFTER:
                # Enough consistent samples: this pair is the pair. Stop adapting.
                # Freezing used to wait for a verified chunk, but a chunk cannot
                # verify until the colours stop moving - so it never froze.
                self.frozen = True
                return
        else:
            # BOOTSTRAP GATE. Before a fit exists there is nothing yet to reject
            # outliers against, so without this a weak/distant signal lets a
            # saturated, flickering piece of background (a red cable, leather,
            # anything warm-coloured) seed the very first samples - and once
            # ready() goes true the outlier check above starts DEFENDING that
            # wrong fit instead of catching it. HUE_ACCEPT/HUE_ONE_HOME/
            # HUE_ZERO_HOME exist for exactly this and must run here, not just
            # sit in a comment - loosely centred on where the LEDs plausibly
            # render (green/blue), wide enough for real camera drift, narrow
            # enough to still exclude red/orange.
            if min(hue_distance(hue, HUE_ONE_HOME),
                   hue_distance(hue, HUE_ZERO_HOME)) > HUE_ACCEPT:
                return
        self.samples.append(hue)
        if len(self.samples) > self.MAX_SAMPLES:
            self.samples.pop(0)
        self._fit()

    def _fit(self):
        if len(self.samples) < self.MIN_SAMPLES:
            return
        self.centre = circular_mean_hue(np.array(self.samples, dtype=np.float32))
        xs = sorted(self._rot(h) for h in self.samples)
        # widest gap between consecutive samples = the boundary between the two colours
        best_gap, best_at = 0.0, None
        for i in range(len(xs) - 1):
            g = xs[i + 1] - xs[i]
            if g > best_gap:
                best_gap, best_at = g, (xs[i] + xs[i + 1]) / 2.0
        if best_gap < self.MIN_VOID or best_at is None:
            return                      # only one colour seen so far - not ready
        lo = [x for x in xs if x <= best_at]
        hi = [x for x in xs if x > best_at]
        if min(len(lo), len(hi)) < len(xs) * 0.12:
            return
        # Trimmed refit: throw away the worst-fitting samples and fit again. Frames
        # where the tracked box clipped something else (a red cable brushing past)
        # otherwise drag a centre badly - a 60 degree pair was measured as 81 with a
        # centre pulled down to 39. Outliers get discarded, not averaged in.
        lo_c = sum(lo) / len(lo)
        hi_c = sum(hi) / len(hi)
        for _ in range(2):
            resid = sorted(min(abs(x - lo_c), abs(x - hi_c)) for x in xs)
            cutoff = resid[max(0, int(len(resid) * 0.8) - 1)]
            keep = [x for x in xs if min(abs(x - lo_c), abs(x - hi_c)) <= max(cutoff, 4.0)]
            lo2 = [x for x in keep if abs(x - lo_c) <= abs(x - hi_c)]
            hi2 = [x for x in keep if abs(x - lo_c) > abs(x - hi_c)]
            if min(len(lo2), len(hi2)) < 3:
                break
            lo_c, hi_c = sum(lo2) / len(lo2), sum(hi2) / len(hi2)
        if (hi_c - lo_c) < self.MIN_CENTRE_SEP:
            return
        self.threshold = (lo_c + hi_c) / 2.0
        self.lo_c = lo_c
        self.hi_c = hi_c

    def ready(self):
        return self.threshold is not None

    def classify(self, hue, now_ms=None):
        """Returns 'A' or 'B' - deliberately not 'one' or 'zero'."""
        if not self.ready():
            return None
        x = self._rot(hue)
        # A LIT frame is always one of the two colours - nearest wins, full stop.
        # This used to reject "outliers" and the caller turned that into DARK,
        # which meant a blue phase reading slightly off got eaten as a separator
        # and every header shifted by a bit. Dark is a brightness question and
        # BoxReader answers it; hue only ever chooses between A and B.
        sym = "A" if x <= self.threshold else "B"
        # Count runs in BITS, not frames. Each bit is held for several frames, so
        # counting frames made two identical bits look like a long run and kept
        # destroying a perfectly good fit (seen as refit counts in the hundreds).
        # A dark gap since the last lit frame means a new bit has started.
        new_bit = True
        if now_ms is not None:
            if self._last_lit_ms is not None and (now_ms - self._last_lit_ms) < self.BIT_GAP_MS:
                new_bit = False
            self._last_lit_ms = now_ms
        if not new_bit:
            return sym
        if sym == self.run_sym:
            self.run_len += 1
            if self.run_len > self.MAX_RUN:
                # A long identical run means both colours are landing on the same
                # side of the split. Text never looks like this, so the fit is
                # wrong - discard it and rebuild from fresh samples.
                self.threshold = None
                self.samples = self.samples[-self.MIN_SAMPLES:]
                self.run_sym, self.run_len = None, 0
                self.refits += 1
                self.frozen = False
                self.stable = 0
                return None
        else:
            self.run_sym, self.run_len = sym, 1
        return sym

    def matches(self, hue):
        """Is this hue one of the learned colours? Side-effect free, unlike
        classify() - used to screen candidates before any of them is chosen."""
        if not self.ready():
            return True          # still learning: do not filter, or it cannot bootstrap
        x = self._rot(hue)
        half = (self.hi_c - self.lo_c) / 2.0
        return min(abs(x - self.lo_c), abs(x - self.hi_c)) <= max(half * 0.9,
                                                                  OUTLIER_MIN_MARGIN)

    def distance_to_nearest(self, hue):
        if not self.ready():
            return 0.0
        x = self._rot(hue)
        return min(abs(x - self.lo_c), abs(x - self.hi_c))

    def describe(self):
        if not self.ready():
            return "learning ({} samples)".format(len(self.samples))
        if self.frozen:
            return "{:.0f}|{:.0f} gap{:.0f} LOCKED".format(
                (self.centre + self.lo_c) % 180, (self.centre + self.hi_c) % 180,
                self.hi_c - self.lo_c)
        return "{:.0f}|{:.0f} gap{:.0f} refit{}".format(
            (self.centre + self.lo_c) % 180, (self.centre + self.hi_c) % 180,
            self.hi_c - self.lo_c, self.refits)


class Tracker:
    """A persistent lock on the transmitter, with continuity enforced.

    Detection used to be decided fresh every frame, so a single bad frame could
    throw the lock to the far side of the image and back - corrupting a bit and
    failing the chunk CRC. A transmitter held in the hand does not teleport, so:

      * once locked, only candidates NEAR the lock are considered at all
      * a candidate somewhere else is ignored, no matter how good it looks
      * finding nothing near the lock means the transmitter is in its dark
        phase - the lock is kept, not surrendered
      * moving the lock requires sustained evidence over many frames, never one

    Acquiring the lock is deliberately slow too: a location must produce
    candidates repeatedly before it is believed."""

    ACQUIRE_HITS = 8         # frames of agreement needed to take a lock
    MOVE_HITS = 24           # far more needed to abandon a lock for somewhere else
    LOST_MS = 2500           # after this long with nothing near, the lock is stale
    PERIOD_MIN_MS = 120      # the transmitter alternates at LINK_SPEED_MS per phase. Only
    PERIOD_MAX_MS = 900      # accept blinking inside this band - mains flicker and camera
                             # noise sit far below it, deliberate hand movement far above.
    MIN_PERIODS = 3          # this many measured intervals before the rhythm is believed
    TWO_COLOUR_SPREAD = 16   # a location must show hues at least this far apart before it
                             # can take the lock. This is the transmitter's real
                             # signature: it alternates between TWO colours at one spot.
                             # A red cable, a mouth or a shirt print is ONE hue no matter
                             # how much it flickers with motion, so it never qualifies.
                             # Dropping this check is what let those steal the lock.

    def __init__(self):
        self.pos = None
        self.size = 60.0
        self.last_seen_ms = -1e9
        self.cands = {}      # rounded position -> [hits, last_ms]

    @staticmethod
    def _box_of(cand):
        return cand[-1]      # candidates keep the box last, whatever else changes

    def _key(self, box):
        cx, cy = box[0] + box[2] / 2.0, box[1] + box[3] / 2.0
        return (int(cx // 40), int(cy // 40))

    def _bump(self, box, hue, now_ms, need):
        """Record a sighting at a location and report whether that location has
        earned a lock: seen often enough AND showing two distinct colours."""
        k = self._key(box)
        hits, last, hues, changes = self.cands.get(k, (0, now_ms, [], []))
        if now_ms - last > 1200:
            hits, hues, changes = 0, [], []
        hits += 1
        # log the moment the colour at this spot changes - that is its blink rhythm
        if hues and hue_distance(hue, hues[-1]) > 12:
            changes = (changes + [now_ms])[-10:]
        hues = (hues + [hue])[-30:]
        self.cands[k] = (hits, now_ms, hues, changes)
        for kk in list(self.cands):
            if now_ms - self.cands[kk][1] > 2500:
                del self.cands[kk]
        if hits < need or len(hues) < 6:
            return False
        # RHYTHM. The transmitter changes colour at a steady, known pace. Mains
        # flicker is far faster, a moving object far slower and irregular. Requiring
        # the observed rhythm to sit in the expected band rejects both without
        # needing to know anything about what the object looks like.
        if len(changes) >= self.MIN_PERIODS + 1:
            gaps = [b - a for a, b in zip(changes, changes[1:])]
            gaps.sort()
            median = gaps[len(gaps) // 2]
            if not (self.PERIOD_MIN_MS <= median <= self.PERIOD_MAX_MS):
                return False
        elif len(changes) < 2:
            return False       # nothing has actually blinked here yet
        xs = sorted(hues)
        # robust spread - ignore the extremes so one stray frame cannot qualify a
        # single-coloured object
        lo = xs[max(0, int(len(xs) * 0.15))]
        hi = xs[min(len(xs) - 1, int(len(xs) * 0.85))]
        return (hi - lo) >= self.TWO_COLOUR_SPREAD

    def max_jump(self):
        return max(55.0, 0.9 * self.size)

    def update(self, candidates, now_ms):
        """Returns the candidate to believe this frame, or None."""
        locked = self.pos is not None and (now_ms - self.last_seen_ms) < self.LOST_MS
        if locked:
            near = []
            for c in candidates:
                bx = self._box_of(c)
                cx, cy = bx[0] + bx[2] / 2.0, bx[1] + bx[3] / 2.0
                if abs(cx - self.pos[0]) <= self.max_jump() and \
                        abs(cy - self.pos[1]) <= self.max_jump():
                    near.append(c)
            if near:
                best = max(near, key=lambda c: c[0])
                bx = self._box_of(best)
                cx, cy = bx[0] + bx[2] / 2.0, bx[1] + bx[3] / 2.0
                self.pos = (0.6 * self.pos[0] + 0.4 * cx, 0.6 * self.pos[1] + 0.4 * cy)
                self.size = 0.7 * self.size + 0.3 * max(bx[2], bx[3])
                self.last_seen_ms = now_ms
                self.cands.clear()
                return best
            # nothing near the lock: the transmitter is dark. Keep the lock, and
            # only start building a case for somewhere else - slowly.
            for c in candidates:
                if self._bump(self._box_of(c), c[1], now_ms, self.MOVE_HITS):
                    bx = self._box_of(c)
                    self.pos = (bx[0] + bx[2] / 2.0, bx[1] + bx[3] / 2.0)
                    self.size = max(bx[2], bx[3])
                    self.last_seen_ms = now_ms
                    self.cands.clear()
                    return c
            return None
        # no lock: require a location to prove itself over several frames
        for c in sorted(candidates, key=lambda c: -c[0]):
            if self._bump(self._box_of(c), c[1], now_ms, self.ACQUIRE_HITS):
                bx = self._box_of(c)
                self.pos = (bx[0] + bx[2] / 2.0, bx[1] + bx[3] / 2.0)
                self.size = max(bx[2], bx[3])
                self.last_seen_ms = now_ms
                self.cands.clear()
                return c
        return None

    def reset(self):
        self.pos = None
        self.size = 60.0
        self.last_seen_ms = -1e9
        self.cands = {}

    def box(self):
        if self.pos is None:
            return None
        s = max(20.0, self.size)
        return (int(self.pos[0] - s / 2), int(self.pos[1] - s / 2), int(s), int(s))

    def has_lock(self, now_ms):
        return self.pos is not None and (now_ms - self.last_seen_ms) < self.LOST_MS

    def touch(self, now_ms):
        """The box read lit this frame: the transmitter is still there."""
        if self.pos is not None:
            self.last_seen_ms = now_ms


class FlickerMap:
    """Finds WHERE the transmitter is by looking for pixels whose colour keeps
    changing, and nothing else.

    Every previous version detected by appearance - saturation, hue, shape - and
    then tried to reject impostors afterwards with a blink check. That ordering is
    backwards and it never stopped failing: a printer, a face and a shirt print all
    look enough like a coloured blob to win detection, and by the time the blink
    check ran the lock had already moved. A static object cannot fake temporal
    change, so measuring change FIRST removes that whole class of impostor before
    anything is compared.

    Cheap by design: frames are downscaled hard, and the measure is simply the
    spread between the brightest and darkest recent value at each pixel."""

    def __init__(self):
        self.hist = []
        self.scale = 0.25

    def update(self, frame_bgr):
        small = cv2.resize(frame_bgr, None, fx=self.scale, fy=self.scale,
                           interpolation=cv2.INTER_AREA)
        hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
        # track saturation*value: the LED swings from a bright saturated colour to
        # dark, which moves this a lot, while a static print barely moves it at all
        metric = (hsv[:, :, 1].astype(np.float32) * hsv[:, :, 2].astype(np.float32)) / 255.0
        self.hist.append(metric)
        if len(self.hist) > FLICKER_HISTORY:
            self.hist.pop(0)

    def mask(self, shape):
        if len(self.hist) < 4:
            return None
        stack = np.stack(self.hist, axis=0)
        spread = stack.max(axis=0) - stack.min(axis=0)
        m = (spread > FLICKER_MIN).astype(np.uint8) * 255
        m = cv2.dilate(m, np.ones((5, 5), np.uint8))
        return cv2.resize(m, (shape[1], shape[0]), interpolation=cv2.INTER_NEAREST)


class BoxReader:
    """Reads the symbol directly off the LOCKED box, every frame, without
    contours, candidate scoring or colour screening.

    Why this exists: once locked, the old path still re-detected a blob every
    frame and read the bit from whatever contour won. At 1.5 m that contour
    was a handful of pixels, the 7x7 close fused it with the cable the device
    illuminates, and the erosion left a different subset of pixels each
    frame - so the same phase produced a different hue every frame. Worse, a
    lit frame whose hue was "not one of the two colours" was reported as
    DARK, so a blue phase that read a little off got eaten as a separator
    and every header was misaligned.

    The protocol has exactly two facts per frame and this reads exactly
    those:
      dark or lit  -> brightness of the brightest pixels in the box, against
                      the swing seen there recently. Colour never decides this.
      which colour -> circular mean hue of those same brightest pixels. The
                      brightest pixels are the LEDs themselves, not the bag
                      they illuminate and not the cable next to them, so the
                      reading is stable frame to frame even if it is not the
                      "true" LED hue. Stable is all the splitter needs."""

    HIST = 60            # frames of brightness history (~2 s), spans several bits
    MIN_SWING = 25       # box must have shown at least this much lit/dark swing
                         # before it is trusted - a static patch never gets there
    DARK_FRAC = 0.4      # below this fraction of the swing = dark
    CROP = 0.2           # trim this fraction off each side of the box first
    TOP_FRAC = 0.25      # brightest quarter of the cropped box = the LEDs
    HUE_MIN_SAT = 40     # a top pixel must be at least this saturated to vote on hue

    def __init__(self):
        self.vals = []

    def read(self, hsv, box):
        """Returns (lit, hue, sat, vtop, swing). lit is None while settling."""
        H, W = hsv.shape[:2]
        bx, by, bw, bh = box
        mx, my = int(bw * self.CROP), int(bh * self.CROP)
        x0, y0 = max(0, bx + mx), max(0, by + my)
        x1, y1 = min(W, bx + bw - mx), min(H, by + bh - my)
        if x1 - x0 < 3 or y1 - y0 < 3:
            return None, None, 0.0, 0.0, 0.0
        reg = hsv[y0:y1, x0:x1]
        v = reg[:, :, 2].ravel().astype(np.float32)
        s = reg[:, :, 1].ravel()
        h = reg[:, :, 0].ravel()
        k = max(4, int(len(v) * self.TOP_FRAC))
        idx = np.argpartition(v, -k)[-k:]
        vtop = float(v[idx].mean())
        self.vals = (self.vals + [vtop])[-self.HIST:]
        lo, hi = min(self.vals), max(self.vals)
        swing = hi - lo
        if len(self.vals) < 8 or swing < self.MIN_SWING:
            return None, None, 0.0, vtop, swing
        lit = vtop > lo + self.DARK_FRAC * swing
        sel = idx[s[idx] > self.HUE_MIN_SAT]
        if len(sel) < 3:
            sel = idx
        hue = float(circular_mean_hue(h[sel]))
        sat = float(s[sel].mean())
        return lit, hue, sat, vtop, swing

    def reset(self):
        self.vals = []


def find_and_read(frame_bgr, last_box, last_lock_ms, now_ms, sat_min,
                  activity=None, flicker=None, tracker=None, colors=None, reader=None):
    """Read one symbol. Detection is restricted to regions that are actually
    flickering, so static objects are never candidates in the first place.

    Returns (cls, info, box, last_lock_ms, peak_sat, search_mode). cls is
    ONE / ZERO / SYNC, where SYNC means the transmitter is dark between bits."""
    H, W = frame_bgr.shape[:2]
    info = {"reason": "", "delta": None, "hue": None, "sat": 0, "v": 0,
            "corner_v": None, "orient": "-", "score": 0.0, "flicker_area": 0}

    hsv_full = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
    brightish = hsv_full[:, :, 2] > 70
    peak_sat = int(np.percentile(hsv_full[:, :, 1][brightish], 99)) if brightish.sum() > 50 \
        else int(hsv_full[:, :, 1].max())

    flick = flicker.mask(frame_bgr.shape) if flicker is not None else None
    if flick is None:
        info["reason"] = "measuring flicker..."
        return None, info, last_box, last_lock_ms, peak_sat, "FULL"
    info["flicker_area"] = int(cv2.countNonZero(flick))

    colour = cv2.inRange(hsv_full, (0, max(40, min(sat_min, BLOB_MIN_SAT)), VAL_MIN),
                         (179, 255, 255))
    mask = cv2.bitwise_and(colour, flick)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    frame_area = float(H * W)
    found = []
    for c in contours:
        area = cv2.contourArea(c)
        if area < FLICKER_MIN_AREA or area > frame_area * MAX_AREA_FRAC:
            continue
        bx, by, bw, bh = cv2.boundingRect(c)
        if bw < 10 or bh < 10:
            continue
        # SHAPE. The matrix is a solid square; a cable is a thin line. I dropped
        # these checks when detection was rewritten around flicker, which is exactly
        # when the cable started winning the lock.
        #
        # Aspect alone is not enough: a DIAGONAL cable has a nearly square bounding
        # box (measured 1.31) and sails through an aspect test. What separates them
        # is how much of that box the object actually fills - the matrix fills 98%,
        # the diagonal cable 16%.
        if max(bw, bh) / float(min(bw, bh)) > SHAPE_ASPECT_MAX:
            continue
        if area / float(bw * bh) < SHAPE_MIN_EXTENT:
            continue
        cm = np.zeros(mask.shape, np.uint8)
        cv2.drawContours(cm, [c], -1, 255, -1)
        k = max(1, int(min(bw, bh) * 0.2)) | 1
        core = cv2.erode(cm, np.ones((k, k), np.uint8))
        if cv2.countNonZero(core) < 25:
            core = cm
        ys, xs = np.nonzero(core)
        if len(xs) < 25:
            continue
        hue = float(circular_mean_hue(hsv_full[ys, xs, 0]))
        sat = float(hsv_full[ys, xs, 1].mean())
        val = float(hsv_full[ys, xs, 2].mean())
        if sat < BLOB_MIN_SAT:
            continue
        # Screen candidates against the LEARNED colours. Until the model has learned
        # anything this passes everything through, so bootstrap still works - but once
        # the transmitter's own two colours are known, nothing else can be a candidate.
        #
        # Leaving this out was why an orange cable, a keyboard and a white basket kept
        # taking the lock: candidates were scored on saturation and brightness alone,
        # and a red cable is more saturated than a washed-out LED, so it simply won.
        if colors is not None and not colors.matches(hue):
            continue
        closeness = 0.0
        if colors is not None and colors.ready():
            closeness = max(0.0, 30.0 - colors.distance_to_nearest(hue))
        found.append((closeness * 2.0 + sat * 0.03 + val * 0.01, hue, sat, val,
                      (bx, by, bw, bh)))

    chosen = tracker.update(found, now_ms) if tracker is not None else (
        max(found, key=lambda c: c[0]) if found else None)

    # ---- LOCKED: read the bit off the box itself, ignore contours from here ----
    if reader is not None and tracker is not None and tracker.has_lock(now_ms):
        box = tracker.box()
        lit, hue, sat, vtop, swing = reader.read(hsv_full, box)
        info.update({"v": vtop, "blink_swing": swing, "box_swing": swing,
                     "colours": colors.describe() if colors else "-"})
        if lit is None:
            info["reason"] = "settling (swing {:.0f})".format(swing)
            return None, info, box, now_ms, peak_sat, "FULL"
        if not lit:
            info["reason"] = "dark (separator)"
            info["active"] = True
            return "SYNC", info, box, now_ms, peak_sat, "FULL"
        # lit: the box is bright, so the transmitter is on. Keep the lock alive
        # from this, not from whether a contour happened to win this frame.
        tracker.touch(now_ms)
        name = colors.classify(hue, now_ms) if colors is not None else None
        if colors is not None:
            colors.observe(hue)
        if activity is not None:
            activity.observe(box, name or "LIT", now_ms, vtop)
            trusted, n_states, swing_a = activity.evidence(box, now_ms)
            info["active"] = trusted
            info["states_seen"] = n_states
        else:
            info["active"] = True
        info.update({"hue": hue, "sat": sat, "corner_v": vtop, "delta": 0,
                     "colours": colors.describe() if colors else "-",
                     "reason": "" if name else "calibrating colours"})
        return name, info, box, now_ms, peak_sat, "FULL"

    if chosen is None:
        # Either nothing is lit anywhere (the transmitter's dark phase, a real
        # symbol) or the only lit things are somewhere the transmitter is not, in
        # which case they are ignored rather than believed. Both mean SYNC here,
        # and crucially the lock does not move.
        has_lock = tracker.has_lock(now_ms) if tracker is not None else False
        info["reason"] = "dark (separator)" if has_lock else "acquiring lock..."
        box_out = tracker.box() if tracker is not None else last_box
        if not has_lock:
            return None, info, box_out, last_lock_ms, peak_sat, "FULL"
        info["active"] = True
        return "SYNC", info, box_out, now_ms, peak_sat, "FULL"

    score, hue, sat, val, box = chosen
    box = tracker.box() if tracker is not None else box
    name = colors.classify(hue, now_ms) if colors is not None else None
    if activity is not None:
        activity.observe(box, name or "LIT", now_ms, val)
        trusted, n_states, swing = activity.evidence(box, now_ms)
        info["active"] = trusted
        info["states_seen"] = n_states
        info["blink_swing"] = swing
    else:
        info["active"] = True
    # Learn colours only from the tracked, blinking transmitter - never from
    # whatever else happens to be lit in the room.
    if colors is not None:
        colors.observe(hue)
    info.update({"hue": hue, "sat": sat, "v": val, "score": score, "corner_v": val,
                 "delta": 0, "colours": colors.describe() if colors else "-"})
    if name is None:
        # The blob at the tracked spot is not one of the transmitter's colours -
        # something else drifted into frame while the transmitter is dark. That is
        # the separator, not an unreadable frame. Returning "nothing" here dropped
        # roughly a quarter of the dark phases and broke the bit framing, so no
        # header ever assembled even though the colours were being read correctly.
        has_lock = tracker.has_lock(now_ms) if tracker is not None else False
        info["reason"] = "outlier colour - treating as dark" if has_lock else "calibrating"
        if has_lock:
            info["active"] = True
            return "SYNC", info, box, now_ms, peak_sat, "FULL"
        return None, info, box, now_ms, peak_sat, "FULL"
    return name, info, box, now_ms, peak_sat, "FULL"


class ActivityGate:
    """Only trusts a location once it has been seen showing more than one
    distinct state. The real transmitter alternates SYNC/ONE/ZERO forever; a
    static object shows one state and never changes. Structure alone cannot
    tell them apart, because a uniformly coloured square is a structurally
    valid SYNC frame - corners and interior identical is exactly what SYNC
    looks like. Behaviour over time is the only reliable discriminator."""

    def __init__(self):
        self.tracks = []

    @staticmethod
    def _swing(vals):
        if len(vals) < BLINK_MIN_SAMPLES:
            return 0.0
        s = sorted(vals)
        lo = s[max(0, int(len(s) * 0.15))]
        hi = s[min(len(s) - 1, int(len(s) * 0.85))]
        return hi - lo

    def _find(self, box):
        bx, by, bw, bh = box
        cx, cy = bx + bw / 2, by + bh / 2
        for t in self.tracks:
            tx, ty, tw, th = t["box"]
            if abs((tx + tw / 2) - cx) < 100 and abs((ty + th / 2) - cy) < 100:
                return t
        return None

    def evidence(self, box, now_ms):
        """(trusted, n_states, swing) for a location without recording anything."""
        self.tracks = [t for t in self.tracks if now_ms - t["last_ms"] < ACTIVITY_WINDOW_MS]
        t = self._find(box)
        if t is None:
            return False, 0, 0.0
        states = {k: v for k, v in t["states"].items() if now_ms - v < ACTIVITY_WINDOW_MS}
        swing = self._swing(t["vs"])
        return (len(states) >= ACTIVITY_MIN_STATES) or (swing >= BLINK_MIN_SWING), len(states), swing

    def observe(self, box, cls, now_ms, corner_v=None):
        """Returns (trusted, n_states, blink_swing). Trust can be earned two
        independent ways: by decoding several distinct states (needs working
        hue), or by seeing the corners blink their rhythm (works purely in
        the brightness channel, so it survives conditions that make hue
        unreadable). Either is enough - a static object satisfies neither."""
        self.tracks = [t for t in self.tracks if now_ms - t["last_ms"] < ACTIVITY_WINDOW_MS]
        t = self._find(box)
        if t is None:
            t = {"box": box, "states": {}, "last_ms": now_ms, "vs": []}
            self.tracks.append(t)
        t["box"] = box
        t["last_ms"] = now_ms
        if cls is not None:
            t["states"][cls] = now_ms
        t["states"] = {k: v for k, v in t["states"].items() if now_ms - v < ACTIVITY_WINDOW_MS}
        if corner_v is not None:
            t["vs"].append(corner_v)
            if len(t["vs"]) > 40:
                t["vs"].pop(0)
        swing = self._swing(t["vs"])
        trusted = (len(t["states"]) >= ACTIVITY_MIN_STATES) or (swing >= BLINK_MIN_SWING)
        return trusted, len(t["states"]), swing


class Decoder:
    """Run-length decoder over SYNC/ONE/ZERO states. Each confirmed run that
    ends on a data state emits one bit; SYNC runs are just separators."""

    def __init__(self):
        self.current = None
        self.run_len = 0
        self.window = []
        self.collected_bits = []
        self.packet_bits_needed = None
        self.idx = self.total = self.length = self.crc_expected = None
        self.last_activity = 0
        self.bit_log = []
        self.bit_times = []
        self.total_bits = 0        # telemetry: everything below is for the HUD only
        self.header_attempts = 0
        self.last_header = None
        self.chunk_attempts = 0

    def feed(self, now_ms, cls):
        result = None
        if self.collected_bits and (now_ms - self.last_activity) > STALE_MS:
            self.collected_bits = []
            self.packet_bits_needed = None
        if cls is None:
            return None    # no information this frame, not a state change

        self.window.append(cls)
        if len(self.window) > VOTE_WINDOW:
            self.window.pop(0)
        self.run_len += 1

        counts = {}
        for c in self.window:
            counts[c] = counts.get(c, 0) + 1
        winner = max(counts, key=counts.get)
        if winner == self.current or counts[winner] < VOTE_MIN:
            return None

        finished = self.current
        self.current, self.run_len = winner, counts[winner]
        if finished in ("ONE", "ZERO"):
            result = self._push_bit(1 if finished == "ONE" else 0, now_ms)
        return result

    def _push_bit(self, bit, now_ms):
        result = None
        self.total_bits += 1
        self.bit_log.append(bit)
        if len(self.bit_log) > 64:
            self.bit_log.pop(0)
        self.bit_times.append(now_ms)
        if len(self.bit_times) > 12:
            self.bit_times.pop(0)
        self.last_activity = now_ms
        if DEBUG:
            print("[bit] {}  ascii={}".format(bit, bits_to_ascii(self.bit_log)))

        if self.packet_bits_needed is None:
            self.collected_bits.append(bit)
            if len(self.collected_bits) > 40:
                self.collected_bits.pop(0)
            if len(self.collected_bits) == 40:
                b = self.collected_bits
                idx, total, length = bits_to_int(b[0:8]), bits_to_int(b[8:16]), bits_to_int(b[16:24])
                header_crc, payload_crc = bits_to_int(b[24:32]), bits_to_int(b[32:40])
                plausible = length <= CHUNK_SIZE and total != 0 and idx < total
                crc_ok = crc8(bytes([idx, total, length])) == header_crc
                self.header_attempts += 1
                self.last_header = (idx, total, length, plausible, crc_ok)
                if DEBUG:
                    print("[header] idx={} total={} len={} ok={}".format(idx, total, length, crc_ok))
                if plausible and crc_ok:
                    self.idx, self.total, self.length = idx, total, length
                    self.crc_expected = payload_crc
                    self.packet_bits_needed = 40 + length * 8
        else:
            self.collected_bits.append(bit)

        if self.packet_bits_needed is not None and len(self.collected_bits) >= self.packet_bits_needed:
            payload_bits = self.collected_bits[40:40 + self.length * 8]
            payload = bytes(bits_to_int(payload_bits[i:i + 8]) for i in range(0, len(payload_bits), 8))
            ok = crc8(payload) == self.crc_expected
            self.chunk_attempts += 1
            # hand the payload over even on CRC failure. For reading text, a partly
            # wrong chunk shown as unverified beats showing nothing at all - the
            # reader can see most of the message immediately, and it gets replaced
            # the moment a clean copy of that chunk arrives.
            result = (ok, self.idx, self.total, payload)
            self.collected_bits = []
            self.packet_bits_needed = None
        return result

    def measured_ms_per_bit(self):
        if len(self.bit_times) < 2:
            return None
        diffs = [b - a for a, b in zip(self.bit_times, self.bit_times[1:])]
        return sum(diffs) / len(diffs)


class Assembler:
    """Holds verified chunks, and separately the unverified ones, so a partial
    message can be shown while it is still being received. A verified chunk always
    wins over an unverified one at the same position, and once verified a position
    is never downgraded."""

    def __init__(self):
        self.total = None
        self.chunks = {}       # CRC-verified
        self.tentative = {}    # CRC failed, shown as unverified
        self._pending = {}     # candidate new message lengths awaiting a second vote

    def add(self, idx, total, payload, verified=True):
        """Only a CRC-VERIFIED chunk is allowed to define how long the message is.

        The header carries a 1-byte CRC, so a random 40-bit window passes it
        about once every 256 tries - and the receiver tries a new window on
        every bit. After a couple of hundred attempts a false header is not
        unlucky, it is expected. Seen live: a bogus header claiming total=2
        arrived, the assembler believed it, and it wiped the verified text and
        reset the progress bar to 0/2.

        An unverified chunk now cannot change the message length or clear
        anything. It can only fill a slot inside a structure that a verified
        chunk already established."""
        already_complete = self.total is not None and len(self.chunks) == self.total
        if verified and self.total is not None and total != self.total:
            # An established message must not be wiped by one stray chunk, but a real
            # new transmission has to be able to take over. Require TWO verified chunks
            # agreeing on the new length before switching - noise does not pass the
            # payload CRC twice with the same wrong total. This applies whether or not
            # the current message is complete: a half-received one was still being
            # reset by a single false-CRC hit.
            cnt = self._pending.get(total, 0) + 1
            self._pending = {total: cnt}
            if cnt < 2:
                return None
            self._pending = {}
            self.total = total
            self.chunks = {}
            self.tentative = {}
        if not verified:
            # unverified data is display-only: it must match a structure we already
            # trust, and it may never resize or reset that structure
            if self.total is None or total != self.total or idx >= self.total:
                return None
            if idx not in self.chunks:
                self.tentative[idx] = payload
            return None
        if self.total != total:
            if already_complete:
                return None   # noise-corrupted total must not wipe a finished message
            self.total = total
            self.chunks = {}
            self.tentative = {}
        if idx >= total:
            return None
        self.chunks[idx] = payload
        self.tentative.pop(idx, None)
        if len(self.chunks) == self.total:
            return b"".join(self.chunks[i] for i in range(self.total))
        return None

    def progress(self):
        return "0/0" if self.total is None else "{}/{}".format(len(self.chunks), self.total)

    def segments(self, chunk_size):
        """The message so far as (text, verified) runs, ready to draw. Unverified
        text has non-printable bytes stripped out, since for ASCII we know what a
        plausible character looks like and garbage adds nothing."""
        if self.total is None:
            return []
        segs = []
        for i in range(self.total):
            if i in self.chunks:
                segs.append((self.chunks[i].decode("utf-8", errors="replace"), True))
            elif i in self.tentative:
                txt = "".join(chr(b) if 32 <= b < 127 else "." for b in self.tentative[i])
                segs.append((txt, False))
            else:
                segs.append(("_" * chunk_size, False))
        merged = []
        for txt, ver in segs:
            if merged and merged[-1][1] == ver:
                merged[-1] = (merged[-1][0] + txt, ver)
            else:
                merged.append((txt, ver))
        return merged

    def bytes_received(self):
        return sum(len(v) for v in self.chunks.values())

    def bytes_total(self):
        if self.total is not None and len(self.chunks) == self.total:
            return self.bytes_received()
        return None

    def partial_text(self, chunk_size):
        segs = self.segments(chunk_size)
        return "".join(t for t, _ in segs) if segs else None


class SatAdapter:
    """The only thing that still needs to adapt to lighting is the saturation
    floor used to FIND the matrix. Hue calibration is gone entirely - the
    corner reference in every frame replaced it."""

    def __init__(self, initial):
        self.sat_min = initial
        self.ema = float(initial)
        self.miss_streak = 0

    def update(self, got_read, peak_sat, cell_sat_hint=None):
        if got_read:
            self.miss_streak = 0
            if cell_sat_hint:
                self.ema = self.ema * 0.97 + cell_sat_hint * 0.03
                self.sat_min = max(SAT_FLOOR, min(200.0, self.ema * 0.55))
        else:
            self.miss_streak += 1
            if self.miss_streak > 45:      # about 1.5s of nothing
                self.sat_min = max(SAT_FLOOR, self.sat_min * 0.97)
                if self.miss_streak > 400:
                    self.sat_min = SAT_MIN_INITIAL
                    self.miss_streak = 0


def list_cameras():
    for i in range(6):
        cap = cv2.VideoCapture(i)
        print("Camera index {}: {}".format(i, "available" if cap.isOpened() else "not found"))
        cap.release()


# Symbols are unnamed on purpose - "A" and "B", not "one" and "zero". Which one
# carries a 1 is decided by the CRC, not assumed here.
CLASS_COLORS = {"SYNC": (255, 160, 0), "A": (0, 255, 0), "B": (255, 90, 90),
                "ONE": (0, 255, 0), "ZERO": (255, 90, 90), None: (128, 128, 128)}


def overlay_panel(img, x0, y0, x1, y1, alpha=0.6):
    sub = img[y0:y1, x0:x1]
    cv2.addWeighted(np.zeros_like(sub), alpha, sub, 1 - alpha, 0, dst=sub)


def detect_data_type(assembler):
    if not assembler.chunks:
        return "-"
    raw = b"".join(assembler.chunks.values())
    printable = sum(1 for b in raw if 32 <= b < 127 or b in (9, 10, 13))
    return "TEXT/ASCII" if printable / len(raw) >= 0.85 else "BINARY"


def draw_hud(preview, fps, cls, info, decoder, assembler, peak_sat, chunks_ok, chunks_seen,
             sat_adapter, search_mode, colors=None, symcount=None):
    h, w = preview.shape[:2]
    font = cv2.FONT_HERSHEY_SIMPLEX
    green, amber, red, cyan, gray = (0, 255, 0), (0, 170, 255), (60, 60, 255), (255, 255, 0), (170, 170, 170)

    panel_h = 186
    overlay_panel(preview, 0, 0, w, panel_h, alpha=0.65)
    cv2.rectangle(preview, (0, 0), (w - 1, panel_h), (80, 80, 80), 1)

    status = "LOCKED" if cls is not None else "SEARCHING"
    cv2.putText(preview, "LINK: OPTICAL   STATUS: {}   FPS: {}   [{}]".format(
        status, fps, search_mode), (8, 18), font, 0.5, green if cls else amber, 1)

    ms_per_bit = decoder.measured_ms_per_bit() if decoder is not None else None
    rate_text = "RATE: measuring..." if ms_per_bit is None else \
        "RATE: {:.0f} ms/bit  (~{:.2f} bit/s)".format(ms_per_bit, 1000.0 / ms_per_bit)
    cv2.putText(preview, rate_text, (8, 38), font, 0.5, cyan, 1)

    # 0 chunks attempted is NOT 100% quality - reporting it that way made the HUD
    # claim everything was fine while nothing at all was being decoded.
    if chunks_seen:
        quality = chunks_ok / chunks_seen * 100
        qtext = "QUALITY: {:.0f}%   ERRORS: {}/{} chunks".format(
            quality, chunks_seen - chunks_ok, chunks_seen)
        qcolor = green if quality >= 90 else (amber if quality >= 60 else red)
    else:
        qtext = "QUALITY: -- (no chunks attempted yet)"
        qcolor = gray
    cv2.putText(preview, "{}   SAT: {}/{:.0f}".format(qtext, peak_sat, sat_adapter.sat_min),
        (8, 58), font, 0.5, qcolor, 1)

    # structural read detail - this is the diagnostic that matters now
    cs = info.get("corner_spread")
    isp = info.get("interior_spread")
    dl = info.get("delta")
    cv2.putText(preview, "BLOB hue:{}  sat:{:.0f}  v:{:.0f}  flicker_px:{}  colours {}".format(
        "-" if info.get("hue") is None else "{:.0f}".format(info["hue"]),
        info.get("sat", 0) or 0, info.get("v", 0) or 0, info.get("flicker_area", 0),
        colors.describe() if colors else "-"),
        (8, 78), font, 0.45, gray, 1)

    cv2.putText(preview, "SIGNAL: {}   off-target:{}   states:{}   {}".format(
        cls or "-", "-" if dl is None else "{:.0f}deg".format(dl),
        info.get("states_seen", 0), info.get("reason", "")),
        (8, 98), font, 0.5, CLASS_COLORS.get(cls, (170, 170, 170)), 1)

    cv2.putText(preview, "DATA: {}   RECV: {}/{} bytes".format(
        detect_data_type(assembler), assembler.bytes_received(),
        assembler.bytes_total() if assembler.bytes_total() is not None else "?"),
        (8, 118), font, 0.45, gray, 1)

    # ---- buffer telemetry: what the decoder is actually holding ----
    sc = symcount or {}
    n_a, n_b, n_s = sc.get("A", 0), sc.get("B", 0), sc.get("SYNC", 0)
    bal = "-"
    if n_a + n_b:
        bal = "{:.0f}%".format(100.0 * min(n_a, n_b) / float(n_a + n_b))
    cv2.putText(preview, "SYMBOLS A:{} B:{} SYNC:{}  balance:{}".format(n_a, n_b, n_s, bal),
                (8, 138), font, 0.45, cyan, 1)
    if decoder is not None:
        cv2.putText(preview, "BUFFER bits:{}/40  total_bits:{}  hdr_tries:{}  chunk_tries:{}".format(
            len(decoder.collected_bits), decoder.total_bits,
            decoder.header_attempts, decoder.chunk_attempts),
            (8, 158), font, 0.45, cyan, 1)
        lh = decoder.last_header
        txt = "LAST HDR: none seen yet" if lh is None else \
            "LAST HDR idx:{} total:{} len:{} plausible:{} crc:{}".format(*lh)
        colr = gray if lh is None else (green if lh[4] else amber)
        cv2.putText(preview, txt, (8, 178), font, 0.45, colr, 1)

    bar_x0, bar_x1, bar_y0, bar_y1 = w - 160, w - 10, 14, 30
    frac = (len(assembler.chunks) / assembler.total) if assembler.total else 0.0
    cv2.rectangle(preview, (bar_x0, bar_y0), (bar_x1, bar_y1), (120, 120, 120), 1)
    fill_x = bar_x0 + int((bar_x1 - bar_x0) * frac)
    if fill_x > bar_x0:
        cv2.rectangle(preview, (bar_x0, bar_y0), (fill_x, bar_y1), green if frac >= 1 else amber, -1)
    cv2.putText(preview, assembler.progress(), (bar_x0, bar_y0 - 4), font, 0.45, gray, 1)

    segs = assembler.segments(CHUNK_SIZE)
    if segs:
        overlay_panel(preview, 0, h - 34, w, h, alpha=0.65)
        # draw run by run so verified text reads green and unverified amber, letting
        # you read the message as it arrives and watch parts firm up as they verify
        label = "MSG: "
        x = 8
        scale, thick = 0.6, 2
        cv2.putText(preview, label, (x, h - 11), font, scale, gray, thick)
        x += cv2.getTextSize(label, font, scale, thick)[0][0]
        for txt, ver in segs:
            cv2.putText(preview, txt, (x, h - 11), font, scale,
                        green if ver else amber, thick)
            x += cv2.getTextSize(txt, font, scale, thick)[0][0]
            if x > w - 20:
                break


def receive(camera_index):
    cap = cv2.VideoCapture(camera_index)
    # Which colour means 1 is never assumed - both readings are decoded in
    # parallel and the chunk CRC decides. Guessing wrong would otherwise invert
    # every bit and silently produce garbage that still framed correctly.
    decoders = {"A1": Decoder(), "B1": Decoder()}
    polarity = None
    assembler = Assembler()
    sat_adapter = SatAdapter(SAT_MIN_INITIAL)
    activity = ActivityGate()
    flicker = FlickerMap()
    tracker = Tracker()
    colors = ColorSplitter()
    reader = BoxReader()
    symcount = {"A": 0, "B": 0, "SYNC": 0}
    last_symbol_ms = None      # last time a real data symbol was seen
    REACQUIRE_MS = 6000        # silence this long = give up and hunt from scratch
    last_box, last_lock_ms = None, 0
    chunks_ok = chunks_seen = 0
    t_start = time.time()
    frame_times = []

    print("Watching camera {} for the Atom Matrix, q to quit...".format(camera_index))
    while True:
        ok, frame = cap.read()
        if not ok:
            print("Camera read failed.")
            break
        now_ms = time.time() * 1000
        frame_times = [t for t in frame_times if now_ms - t <= 1000] + [now_ms]
        fps = len(frame_times)

        flicker.update(frame)
        cls, info, last_box, last_lock_ms, peak_sat, search_mode = find_and_read(
            frame, last_box, last_lock_ms, now_ms, sat_adapter.sat_min, activity,
            flicker, tracker, colors, reader)
        sat_adapter.update(cls is not None, peak_sat)

        # find_and_read already recorded evidence for validated candidates and picked a
        # blinking one over a static one. Here we only feed unvalidated frames in (so a
        # tracked location keeps accumulating brightness history) and read the verdict.
        if cls == "SYNC" and last_box is not None:
            # the dark phase is a genuine state, and a location that alternates
            # lit/dark is by definition blinking - which is what proves it is a
            # transmitter and not a printer
            activity.observe(last_box, "SYNC", now_ms, 0.0)
            trusted, n_states, swing = activity.evidence(last_box, now_ms)
            info["active"] = trusted
            info["states_seen"] = n_states
            info["blink_swing"] = swing
        active = info.get("active", False)
        decoder_cls = cls if active else None
        if cls is not None and not active:
            info["reason"] = "static? {} state(s)".format(info.get("states_seen", 0))

        if cls in symcount:
            symcount[cls] += 1
        if cls in ("A", "B"):
            last_symbol_ms = now_ms

        # Data has stopped: throw everything away and re-acquire from scratch rather
        # than clinging to a lock and a colour fit that are no longer producing
        # anything. Cheap to rebuild, and it beats sitting on a dead lock forever.
        if last_symbol_ms is not None and (now_ms - last_symbol_ms) > REACQUIRE_MS:
            print("[{:6.1f}s] signal lost for {:.0f}s - re-acquiring from scratch".format(
                time.time() - t_start, REACQUIRE_MS / 1000.0))
            tracker.reset()
            colors.reset()
            reader.reset()
            activity = ActivityGate()
            flicker = FlickerMap()
            decoders = {"A1": Decoder(), "B1": Decoder()}
            polarity = None
            last_box, last_lock_ms = None, 0
            last_symbol_ms = None
            symcount = {"A": 0, "B": 0, "SYNC": 0}

        results = {}
        for pol, dec in decoders.items():
            if decoder_cls in ("A", "B"):
                one = "A" if pol == "A1" else "B"
                sym = "ONE" if decoder_cls == one else "ZERO"
            else:
                sym = decoder_cls          # SYNC or None passes straight through
            r_ = dec.feed(now_ms, sym)
            if r_ is not None:
                results[pol] = r_

        result = None
        if polarity is not None and polarity in results:
            result = results[polarity]
        else:
            for pol, r_ in results.items():
                if r_[0]:                  # a valid CRC identifies the true polarity
                    polarity = pol
                    result = r_
                    break
            else:
                if results and polarity is None:
                    result = next(iter(results.values()))
        if result is not None:
            ok_decode, idx, total, payload = result
            chunks_seen += 1
            elapsed = time.time() - t_start
            if ok_decode:
                chunks_ok += 1
                print("[{:6.1f}s] chunk {}/{} OK".format(elapsed, idx + 1, total))
                # Real data is flowing, so the colour fit is proven. Stop adapting it -
                # the colours cannot change mid-transmission, and further drift can only
                # break something that is demonstrably working.
                colors.freeze()
                full = assembler.add(idx, total, payload, verified=True)
                if full is not None:
                    print("[{:6.1f}s] MESSAGE COMPLETE -> {}".format(
                        elapsed, full.decode("utf-8", errors="replace")))
            else:
                # keep it anyway, shown as unverified until a clean copy replaces it
                assembler.add(idx, total, payload, verified=False)
                shown = "".join(chr(b) if 32 <= b < 127 else "." for b in payload)
                print("[{:6.1f}s] chunk {} CRC failed ({}/{}) unverified: {}".format(
                    elapsed, idx + 1, chunks_ok, chunks_seen, shown))

        preview = frame.copy()
        if last_box is not None:
            bx, by, bw, bh = last_box
            cv2.rectangle(preview, (bx, by), (bx + bw, by + bh),
                          CLASS_COLORS.get(cls, (170, 170, 170)),
                          3 if (cls is not None and info.get("active")) else 1)
            if cls is not None:
                for r in range(1, 5):
                    cv2.line(preview, (bx, by + int(r * bh / 5)), (bx + bw, by + int(r * bh / 5)),
                             (90, 90, 90), 1)
                    cv2.line(preview, (bx + int(r * bw / 5), by), (bx + int(r * bw / 5), by + bh),
                             (90, 90, 90), 1)
        draw_hud(preview, fps, cls, info, decoders.get(polarity or "A1"), assembler,
                 peak_sat, chunks_ok, chunks_seen, sat_adapter, search_mode, colors, symcount)
        if DEBUG:
            cv2.putText(preview, "bits: " + "".join(str(b) for b in
                        decoders.get(polarity or "A1").bit_log)[-48:],
                        (8, 142), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1)
        cv2.imshow("atom link receiver", preview)
        if (cv2.waitKey(1) & 0xFF) == ord("q"):
            break
    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--list-cameras", action="store_true")
    parser.add_argument("--camera", type=int, default=0)
    args = parser.parse_args()
    list_cameras() if args.list_cameras else receive(args.camera)
