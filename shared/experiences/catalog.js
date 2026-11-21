/* Clean Your TV: the Home layouts ("experiences") everyone shows from one list.
 *
 * The one copy of what each layout is: the TV's page and the Chromecast's launcher draw from it (engine.js,
 * layouts.js), the phone panel's Layout picker shows it (names, descriptions, previews, options), and the helper's
 * and the Chromecast's servers check saved choices against it (tests/experiences_check.py reads this file).
 *
 * "classic" is the Home that was there before: the TV's page and the Chromecast's native launcher draw it
 * themselves, and it is always the fallback. Concept numbers 01-10 and the four follow-ups (T, S, A, C) are the ones
 * in the research atlas, CleanYourTV_10_TV_UI_Layout_Concepts.pdf (docs/experiences.md cites it page by page).
 * Plain ES5: VIDAA's browser is old.
 */
(function (root) {
  'use strict';
  // Strict JSON between the markers: helper/experience.py and the Chromecast's server read it as it is.
  var CATALOG = /*JSON*/{
 "version": 1,
 "keys": [
  "up",
  "down",
  "left",
  "right",
  "ok",
  "back"
 ],
 "experiences": [
  {
   "id": "classic",
   "no": "",
   "name": "Classic",
   "tagline": "The Home you have now.",
   "family": "Fallback",
   "role": "Always available",
   "about": "Clock, blocks, dock and rows, as arranged in the Layout tab.",
   "map": {},
   "options": []
  },
  {
   "id": "liquid",
   "no": "01",
   "name": "Liquid OS",
   "tagline": "Apps behave like a calm, responsive material.",
   "family": "Expressive",
   "role": "Experimental",
   "pdf": [
    5,
    6
   ],
   "about": "Your favourites as soft bubbles. The one in focus grows; the rest make room, always in the same order.",
   "map": {
    "left": "Previous favourite",
    "right": "Next favourite",
    "up": "Sources and inputs",
    "down": "All apps",
    "ok": "Open",
    "back": "Close"
   },
   "options": [
    {
     "key": "count",
     "label": "Bubbles on screen",
     "type": "choice",
     "choices": [
      [
       "5",
       "Five"
      ],
      [
       "7",
       "Seven"
      ]
     ],
     "def": "5"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "channel",
   "no": "02",
   "name": "Channel Zero",
   "tagline": "Your apps on a dial, numbered like channels.",
   "family": "TV-native",
   "role": "Signature",
   "pdf": [
    7,
    8
   ],
   "about": "A numbered dial of Live TV, inputs and apps. Up and Down surf; number keys jump to a channel.",
   "map": {
    "up": "Previous source",
    "down": "Next source",
    "left": "Favourites",
    "right": "Source details",
    "ok": "Open or switch",
    "back": "Back"
   },
   "options": [
    {
     "key": "order",
     "label": "Dial order",
     "type": "choice",
     "choices": [
      [
       "apps",
       "Your apps first, then the TV"
      ],
      [
       "live",
       "The TV first, then your apps"
      ]
     ],
     "def": "apps"
    },
    {
     "key": "apps",
     "label": "Apps on the dial",
     "type": "choice",
     "choices": [
      [
       "favorites",
       "Favourites"
      ],
      [
       "all",
       "Every app"
      ]
     ],
     "def": "favorites"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo (Glass or plain)"
      ],
      [
       "off",
       "Dark"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "typography",
   "no": "03",
   "name": "Typography First",
   "tagline": "A television home that speaks in verbs, not icons.",
   "family": "Editorial",
   "role": "Editorial preset",
   "pdf": [
    9,
    10
   ],
   "about": "Huge words: WATCH, PLAY, LISTEN, SOURCES. Right shows the apps inside the chosen word, by name.",
   "map": {
    "up": "Previous word",
    "down": "Next word",
    "right": "Enter",
    "left": "Back to words",
    "ok": "Choose",
    "back": "Back"
   },
   "options": [
    {
     "key": "question",
     "label": "Headline",
     "type": "text",
     "def": "Watching tonight?",
     "max": 30
    },
    {
     "key": "accent",
     "label": "Accent",
     "type": "choice",
     "choices": [
      [
       "warm",
       "Warm"
      ],
      [
       "cool",
       "Cool"
      ]
     ],
     "def": "warm"
    },
    {
     "key": "groups",
     "label": "What goes in Watch, Play and Listen",
     "type": "groups"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "worlds",
   "no": "04",
   "name": "Floating Worlds",
   "tagline": "A small world for each type of evening.",
   "family": "Spatial",
   "role": "Exploration",
   "pdf": [
    11,
    12
   ],
   "about": "Four islands: Watch, Play, Listen, Sources. OK enters one; your favourites stay one key away.",
   "map": {
    "left": "Previous world",
    "right": "Next world",
    "up": "TV controls",
    "down": "Favourites",
    "ok": "Enter world",
    "back": "Back to the worlds"
   },
   "options": [
    {
     "key": "groups",
     "label": "What goes in Watch, Play and Listen",
     "type": "groups"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "fold",
   "no": "05",
   "name": "The Fold",
   "tagline": "Unfold context instead of scrolling endless rows.",
   "family": "Geometric",
   "role": "Experimental",
   "pdf": [
    13,
    14
   ],
   "about": "Four panels: Live TV, Watch, Play, Inputs. The one in focus unfolds its short list; the others stay put.",
   "map": {
    "left": "Previous panel",
    "right": "Next panel",
    "up": "Shortcuts",
    "down": "All apps",
    "ok": "Open the list",
    "back": "Fold it again"
   },
   "options": [
    {
     "key": "groups",
     "label": "What goes in Watch, Play and Listen",
     "type": "groups"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "four",
   "no": "06",
   "name": "Four Directions",
   "tagline": "Make four arrows the main information architecture.",
   "family": "Remote-native",
   "role": "Usability test",
   "pdf": [
    15,
    16
   ],
   "about": "One key, one place: Up discover, Right sources, Down all apps, Left resume. OK opens what you used last.",
   "map": {
    "up": "Discover",
    "right": "Sources",
    "down": "All apps",
    "left": "Resume",
    "ok": "Last used",
    "back": "Back to the hub"
   },
   "options": [
    {
     "key": "center",
     "label": "OK in the middle opens",
     "type": "choice",
     "choices": [
      [
       "last",
       "The app used last"
      ],
      [
       "first",
       "The first favourite"
      ]
     ],
     "def": "last"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "river",
   "no": "07",
   "name": "The Time River",
   "tagline": "From last watched to what matters next.",
   "family": "Context",
   "role": "Future layer",
   "pdf": [
    17,
    18
   ],
   "about": "Now, Resume, what is next (a match, a prayer time) and your scenes, in time order. Each says what OK does.",
   "map": {
    "left": "Earlier",
    "right": "Later",
    "up": "Sources",
    "down": "All apps",
    "ok": "Do it",
    "back": "Back"
   },
   "options": [
    {
     "key": "sports",
     "label": "A match opens",
     "type": "app",
     "def": ""
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "gallery",
   "no": "08",
   "name": "The Gallery",
   "tagline": "Your apps and today's moments, framed on a lit wall.",
   "family": "Curated",
   "role": "Personal preset",
   "pdf": [
    19,
    20
   ],
   "about": "Four to six framed places (apps, an input, today's photo), each with a large caption.",
   "map": {
    "left": "Previous frame",
    "right": "Next frame",
    "up": "About this frame",
    "down": "The whole collection",
    "ok": "Open",
    "back": "Back"
   },
   "options": [
    {
     "key": "frames",
     "label": "Frames",
     "type": "choice",
     "choices": [
      [
       "4",
       "Four"
      ],
      [
       "5",
       "Five"
      ],
      [
       "6",
       "Six"
      ]
     ],
     "def": "5"
    },
    {
     "key": "photo",
     "label": "Today's photo as a frame",
     "type": "choice",
     "choices": [
      [
       "on",
       "Yes"
      ],
      [
       "off",
       "No"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "aurora",
   "no": "09",
   "name": "Aurora",
   "tagline": "Beautiful by default. Functional on demand.",
   "family": "Ambient",
   "role": "Calm preset",
   "pdf": [
    21,
    22
   ],
   "about": "A quiet clock and light. Any arrow shows a short strip of places; OK opens; Back hides it again.",
   "map": {
    "left": "Show / previous",
    "right": "Show / next",
    "up": "Show / controls",
    "down": "Show / all apps",
    "ok": "Open",
    "back": "Hide"
   },
   "options": [
    {
     "key": "strip",
     "label": "Places in the strip",
     "type": "choice",
     "choices": [
      [
       "4",
       "Four"
      ],
      [
       "5",
       "Five"
      ],
      [
       "6",
       "Six"
      ]
     ],
     "def": "5"
    }
   ],
   "hidden": true,
   "merged_into": "ambient"
  },
  {
   "id": "deck",
   "no": "10",
   "name": "The Command Deck",
   "tagline": "The modern television as an understandable system.",
   "family": "Utility",
   "role": "Power-user mode",
   "pdf": [
    23,
    24
   ],
   "about": "The current source large, the TV's real state beside it (input, playback, VPN), favourites below. Nothing it can't verify.",
   "map": {
    "left": "Favourite left",
    "right": "Favourite right",
    "up": "TV controls",
    "down": "All apps",
    "ok": "Do it",
    "back": "Back to the dock"
   },
   "options": []
  },
  {
   "id": "theater",
   "no": "T",
   "name": "Theater",
   "tagline": "Make tonight your own.",
   "family": "Follow-up · from 03, 08, 09",
   "role": "Recommended default",
   "pdf": [
    25,
    32
   ],
   "about": "Today's photo, a headline, what is playing, and one row: favourites, Live TV, an input, all apps.",
   "map": {
    "left": "Previous",
    "right": "Next",
    "up": "Now playing",
    "down": "All apps",
    "ok": "Open",
    "back": "First in the row"
   },
   "options": [
    {
     "key": "headline",
     "label": "Headline",
     "type": "text",
     "def": "Make tonight your own.",
     "max": 40
    }
   ]
  },
  {
   "id": "signal",
   "no": "S",
   "name": "Signal",
   "tagline": "One place for every source.",
   "family": "Follow-up · variant of 02, 10",
   "role": "Signature (practical)",
   "pdf": [
    25
   ],
   "variantOf": "channel",
   "about": "Channel Zero's dial, grouped: inputs, Live TV and apps under their own headings, today's photo on the stage, no numbers.",
   "map": {
    "up": "Previous source",
    "down": "Next source",
    "left": "Next group",
    "right": "Source details",
    "ok": "Open or switch",
    "back": "Back"
   },
   "options": [],
   "hidden": true,
   "merged_into": "channel"
  },
  {
   "id": "scenes",
   "no": "C",
   "name": "Scene Stage",
   "tagline": "Set the mood.",
   "family": "Follow-up · from 04, 07",
   "role": "Activity",
   "pdf": [
    25
   ],
   "about": "Your scenes as large cards; OK runs one (wake, VPN, an app…). Only the scenes you allow on Home appear.",
   "map": {
    "left": "Previous scene",
    "right": "Next scene",
    "down": "Favourites",
    "up": "Sources",
    "ok": "Start the scene",
    "back": "Back to the scenes"
   },
   "options": [
    {
     "key": "scenes",
     "label": "Scenes allowed on Home",
     "type": "scenes"
    },
    {
     "key": "photo",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "on",
       "Today's photo, dimmed"
      ],
      [
       "off",
       "The layout's own colours"
      ]
     ],
     "def": "on"
    }
   ]
  },
  {
   "id": "ambient",
   "no": "A",
   "name": "Ambient",
   "tagline": "A clock, today's photo, and nothing else until you ask.",
   "family": "Follow-up · variant of 08, 09",
   "role": "Calm (photo)",
   "pdf": [
    25
   ],
   "variantOf": "aurora",
   "about": "Aurora's reveal-on-demand strip over today's photo, with the clock in the middle.",
   "map": {
    "left": "Show / previous",
    "right": "Show / next",
    "up": "Show",
    "down": "Show / all apps",
    "ok": "Open",
    "back": "Hide"
   },
   "options": [
    {
     "key": "background",
     "label": "Background",
     "type": "choice",
     "choices": [
      [
       "photo",
       "Today's photo"
      ],
      [
       "light",
       "Calm light, no photo (was Aurora)"
      ]
     ],
     "def": "photo"
    },
    {
     "key": "strip",
     "label": "Places in the strip",
     "type": "choice",
     "choices": [
      [
       "4",
       "Four"
      ],
      [
       "5",
       "Five"
      ],
      [
       "6",
       "Six"
      ]
     ],
     "def": "4"
    }
   ]
  }
 ],
 "common": [
  {
   "key": "motion",
   "label": "Motion",
   "type": "choice",
   "choices": [
    [
     "full",
     "Gentle (120-180 ms)"
    ],
    [
     "reduced",
     "Reduced: no movement"
    ]
   ],
   "def": "full"
  }
 ]
}/*END*/;
  CATALOG.ids = CATALOG.experiences.map(function (e) { return e.id; });
  CATALOG.get = function (id) {
    for (var i = 0; i < CATALOG.experiences.length; i++) if (CATALOG.experiences[i].id === id) return CATALOG.experiences[i];
    return null;
  };
  root.CYTX_CATALOG = CATALOG;
})(typeof window !== 'undefined' ? window : this);
