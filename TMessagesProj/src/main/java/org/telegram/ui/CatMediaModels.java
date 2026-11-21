package org.telegram.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sample catalog for the Movies & Series page.
 *
 * <p>Deliberately shaped like a real API response — ids, a category, an episode
 * list with durations and stream URLs — so wiring a source later replaces this
 * class and nothing in the UI. Artwork is procedural (see {@link CatMediaPoster}),
 * so the page still needs no image assets.
 */
public final class CatMediaModels {

    public enum Kind {SERIES, FILM, DOCUMENTARY}

    private CatMediaModels() {
    }

    public static final class Title {
        public final String id;
        public final Kind kind;
        public final String name;
        /** Shown under the poster: "Film · 2024", "S2 · E4". */
        public String subtitle;
        /** Longer copy for the detail and hero cards. */
        public String overview;
        public final List<Episode> episodes = new ArrayList<>();
        public final int colorTop;
        public final int colorBottom;
        public final int seed;
        public final int year;
        public float rating;
        /** Genre tags, lower-case, matched against the genre chips. */
        public final List<String> genres = new ArrayList<>();
        /** Director, cast — shown on the detail screen. */
        public final List<Person> people = new ArrayList<>();

        Title(String id, Kind kind, String name, int year, float rating, int colorTop, int colorBottom, int seed) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.year = year;
            this.rating = rating;
            this.colorTop = colorTop;
            this.colorBottom = colorBottom;
            this.seed = seed;
        }

        public boolean isSeries() {
            return kind == Kind.SERIES && episodes.size() > 1;
        }

        public String kindLabel() {
            switch (kind) {
                case SERIES:
                    return "Series";
                case DOCUMENTARY:
                    return "Documentary";
                default:
                    return "Film";
            }
        }

        int totalMinutes() {
            if (episodes.isEmpty()) {
                return 0;
            }
            if (!isSeries()) {
                return episodes.get(0).minutes;
            }
            int total = 0;
            for (Episode e : episodes) {
                total += e.minutes;
            }
            return total;
        }
    }

    public static final class Person {
        public final String name;
        public final String role;

        Person(String name, String role) {
            this.name = name;
            this.role = role;
        }
    }

    /** One selectable stream for an episode. */
    public static final class Quality {
        public final String label;
        public final String url;
        /** Roughly how much a viewer recognises it: used to pick a default. */
        public final int height;

        public Quality(String label, int height, String url) {
            this.label = label;
            this.height = height;
            this.url = url;
        }
    }

    public static final class Episode {
        public final int season;
        public final int number;
        public final String title;
        public final int minutes;
        /** Best stream; what the player starts with when nothing is remembered. */
        public final String url;
        public final List<Quality> qualities = new ArrayList<>();
        /** 0..1, where the viewer stopped. Mirrors {@link CatMediaLibrary}. */
        public float progress;

        Episode(int season, int number, String title, int minutes, String url) {
            this.season = season;
            this.number = number;
            this.title = title;
            this.minutes = minutes;
            this.url = url;
        }

        /** Stable across releases; the key positions are stored under. */
        public String key() {
            return "s" + season + "e" + number;
        }

        public Quality best() {
            return qualities.isEmpty() ? null : qualities.get(0);
        }
    }

    public static final class Category {
        public final String id;
        public final String name;
        public final List<Title> titles;

        Category(String id, String name, List<Title> titles) {
            this.id = id;
            this.name = name;
            this.titles = Collections.unmodifiableList(titles);
        }
    }

    /** The whole catalog, built once. */
    public static List<Category> catalog() {
        return CatalogHolder.CATEGORIES;
    }

    public static List<Title> continueWatching() {
        final List<Title> out = new ArrayList<>();
        for (Title t : allTitles()) {
            if (progressOf(t) > 0f) {
                out.add(t);
            }
        }
        return out;
    }

    /** Every title once, in catalogue order. */
    public static List<Title> allTitles() {
        final List<Title> out = new ArrayList<>();
        for (Category c : catalog()) {
            for (Title t : c.titles) {
                if (!containsId(out, t.id)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * Search across the fields a viewer would type: name, cast, genre and year.
     * Matches as a substring so "ember" finds "The Last Ember" and "2025" finds
     * everything from that year.
     */
    public static List<Title> search(String query) {
        final String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        final List<Title> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        for (Title t : allTitles()) {
            if (matches(t, q)) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean matches(Title t, String q) {
        if (t.name.toLowerCase(java.util.Locale.US).contains(q)) {
            return true;
        }
        if (String.valueOf(t.year).contains(q)) {
            return true;
        }
        for (String genre : t.genres) {
            if (genre.contains(q)) {
                return true;
            }
        }
        for (Person p : t.people) {
            if (p.name.toLowerCase(java.util.Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }

    public static List<Title> byGenre(String genre) {
        final List<Title> out = new ArrayList<>();
        if (genre == null || "$all".equals(genre)) {
            return allTitles();
        }
        for (Title t : allTitles()) {
            if (t.genres.contains(genre)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Genre ids in the order they should appear as chips. */
    public static List<String> genres() {
        final List<String> out = new ArrayList<>();
        for (Title t : allTitles()) {
            for (String g : t.genres) {
                if (!out.contains(g)) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    private static boolean containsId(List<Title> list, String id) {
        for (Title t : list) {
            if (t.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static Title title(String id) {
        for (Category c : catalog()) {
            for (Title t : c.titles) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
        }
        return null;
    }

    public static Episode nextEpisode(Title t) {
        for (Episode e : t.episodes) {
            if (e.progress < 0.99f) {
                return e;
            }
        }
        return t.episodes.isEmpty() ? null : t.episodes.get(0);
    }

    private static float progressOf(Title t) {
        for (Episode e : t.episodes) {
            if (e.progress > 0f && e.progress < 0.99f) {
                return e.progress;
            }
        }
        return 0f;
    }

    /** Stream ladder for a sample title; higher first so best() is qualities[0]. */
    private static void ladder(Title t, String base) {
        final String dir = base.endsWith("/") ? base : base + "/";
        for (Episode e : t.episodes) {
            e.qualities.add(new Quality("1080p", 1080, dir + e.key() + "-1080.m3u8"));
            e.qualities.add(new Quality("720p", 720, dir + e.key() + "-720.m3u8"));
            e.qualities.add(new Quality("480p", 480, dir + e.key() + "-480.m3u8"));
            e.qualities.add(new Quality("360p", 360, dir + e.key() + "-360.m3u8"));
        }
    }

    private static void genres(Title t, String... tags) {
        for (String tag : tags) {
            t.genres.add(tag);
        }
    }

    private static void people(Title t, String director, String... cast) {
        t.people.add(new Person(director, "Director"));
        for (String name : cast) {
            t.people.add(new Person(name, "Cast"));
        }
    }

    private static Title series(String id, String name, int year, float rating, int top, int bottom, int seed,
                                String overview, int seasons, int perSeason, int minutes, String urlBase) {
        final Title t = new Title(id, Kind.SERIES, name, year, rating, top, bottom, seed);
        t.overview = overview;
        for (int s = 1; s <= seasons; s++) {
            for (int n = 1; n <= perSeason; n++) {
                t.episodes.add(new Episode(s, n, "Episode " + n, minutes, urlBase + "/s" + s + "e" + n + ".m3u8"));
            }
        }
        return t;
    }

    private static Title film(String id, String name, int year, float rating, int top, int bottom, int seed,
                              String overview, int minutes, String url) {
        final Title t = new Title(id, Kind.FILM, name, year, rating, top, bottom, seed);
        t.overview = overview;
        t.episodes.add(new Episode(1, 1, name, minutes, url));
        return t;
    }

    private static Title documentary(String id, String name, int year, float rating, int top, int bottom, int seed,
                                     String overview, int minutes, String url) {
        final Title t = new Title(id, Kind.DOCUMENTARY, name, year, rating, top, bottom, seed);
        t.overview = overview;
        t.episodes.add(new Episode(1, 1, name, minutes, url));
        return t;
    }

    private static final class CatalogHolder {
        static final List<Category> CATEGORIES = build();

        private static List<Category> build() {
            final List<Category> out = new ArrayList<>();

            final Title ember = series("last-ember", "The Last Ember", 2024, 8.4f,
                    0xFFB2432E, 0xFF4A1710, 11,
                    "The city goes dark for one night. Nobody agrees on what they saw, and the one person who wrote it down has gone missing.",
                    2, 4, 48, "https://sample.invalid/last-ember");
            ember.episodes.get(0).progress = 1f;
            ember.episodes.get(1).progress = 1f;
            ember.episodes.get(2).progress = 1f;
            ember.episodes.get(3).progress = .63f;
            ember.subtitle = "S2 · E4";

            ladder(ember, "https://sample.invalid/last-ember");
            genres(ember, "drama", "thriller");
            people(ember, "N. Farrish", "Ilse Marchetti", "Owen Reyes", "Hana Petrov");

            final Title watch = series("night-watch", "Night Watch", 2025, 7.9f,
                    0xFF2B4A8C, 0xFF0A1430, 22,
                    "Two patrol officers, one empty street, and a call that comes every night at 3:04.",
                    1, 6, 42, "https://sample.invalid/night-watch");
            watch.episodes.get(0).progress = .31f;
            watch.subtitle = "S1 · E2";

            ladder(watch, "https://sample.invalid/night-watch");
            genres(watch, "drama", "crime");
            people(watch, "R. Ueda", "Greta Lund", "Tom Achterberg");

            final Title copenhagen = film("copenhagen", "Copenhagen", 2024, 8.1f,
                    0xFF6B5B4A, 0xFF241C14, 33,
                    "A cartographer retraces a trip she cannot remember taking, one harbour at a time.",
                    112, "https://sample.invalid/copenhagen.m3u8");

            ladder(copenhagen, "https://sample.invalid/copenhagen");
            genres(copenhagen, "drama", "scifi");
            people(copenhagen, "Ana Bettencourt", "Sofie Dahl");

            final Title quiet = documentary("quiet-stone", "Quiet Stone", 2023, 7.6f,
                    0xFF3A5B52, 0xFF0C1D19, 44,
                    "Four months with the masons restoring a bridge nobody uses.",
                    74, "https://sample.invalid/quiet-stone.m3u8");

            ladder(quiet, "https://sample.invalid/quiet-stone");
            genres(quiet, "documentary");

            final Title fist = series("blue-fist", "Blue Fist", 2025, 7.2f,
                    0xFF25456E, 0xFF08111F, 55,
                    "A retired boxer trains the kid who broke his jaw.",
                    1, 9, 39, "https://sample.invalid/blue-fist");
            fist.episodes.get(7).progress = .12f;
            fist.subtitle = "S1 · E9";

            ladder(fist, "https://sample.invalid/blue-fist");
            genres(fist, "drama", "sport");
            people(fist, "K. Osei", "Milo Grant", "Rasheed Ali");

            final List<Title> featured = new ArrayList<>();
            featured.add(ember);
            featured.add(watch);
            featured.add(copenhagen);
            featured.add(quiet);
            featured.add(fist);
            out.add(new Category("featured", "Featured", featured));

            final List<Title> drama = new ArrayList<>();
            {
                final Title afterHours = series("after-hours", "After Hours", 2024, 7.8f,
                        0xFF7A3F5E, 0xFF2A1220, 66,
                        "The night shift at a hospital that stopped taking new patients weeks ago.",
                        2, 5, 46, "https://sample.invalid/after-hours");
                ladder(afterHours, "https://sample.invalid/after-hours");
                genres(afterHours, "drama", "thriller");
                people(afterHours, "M. Cardoso", "Iris Vane", "Luca Moretti");
                drama.add(afterHours);

                final Title signal = series("signal", "Signal", 2023, 8.0f,
                        0xFF6E2F35, 0xFF230B0E, 88,
                        "A radio host starts receiving tomorrow's news a day early.",
                        1, 8, 44, "https://sample.invalid/signal");
                ladder(signal, "https://sample.invalid/signal");
                genres(signal, "thriller", "scifi");
                people(signal, "P. Halvorsen", "Nadia Karr");

                final Title north = film("copenhagen-2", "Copenhagen: North", 2025, 7.4f,
                        0xFF4A5F7A, 0xFF141B22, 77,
                        "The cartographer's second trip, this time without a map.",
                        98, "https://sample.invalid/copenhagen-north.m3u8");
                ladder(north, "https://sample.invalid/copenhagen-north");
                genres(north, "drama");
                people(north, "Ana Bettencourt", "Sofie Dahl");
                drama.add(north);
                drama.add(signal);
            }

            out.add(new Category("drama", "Drama", drama));

            final Title smallCities = documentary("small-cities", "Small Cities", 2024, 7.9f,
                    0xFF5C6B3A, 0xFF1D2312, 99,
                    "Eight towns of under ten thousand people, and the one shop each of them still needs.",
                    88, "https://sample.invalid/small-cities.m3u8");
            ladder(smallCities, "https://sample.invalid/small-cities");
            genres(smallCities, "documentary");

            final List<Title> docs = new ArrayList<>();
            docs.add(quiet);
            docs.add(smallCities);
            out.add(new Category("documentary", "Documentary", docs));

            final Title orbit = series("orbit", "Orbit", 2025, 8.2f,
                    0xFF2E4B7A, 0xFF0B1322, 133,
                    "A station crew keeps receiving their own distress call, two minutes ahead of schedule.",
                    2, 6, 51, "https://sample.invalid/orbit");
            ladder(orbit, "https://sample.invalid/orbit");
            genres(orbit, "scifi", "thriller");
            people(orbit, "L. Ferreira", "Amara Odiase", "Jun Park");

            final Title toTheNorth = film("to-the-north", "To the North", 2023, 7.5f,
                    0xFF4A5F7A, 0xFF141B22, 77,
                    "The last flight out of a town that is being erased from the map.",
                    104, "https://sample.invalid/to-the-north.m3u8");
            ladder(toTheNorth, "https://sample.invalid/to-the-north");
            genres(toTheNorth, "scifi", "drama");
            people(toTheNorth, "Y. Lindqvist", "Pia Sand");

            final List<Title> scifi = new ArrayList<>();
            scifi.add(orbit);
            scifi.add(toTheNorth);
            out.add(new Category("scifi", "Sci-Fi", scifi));

            final Title liveRoom = documentary("live-room", "Live Room", 2024, 8.0f,
                    0xFF6B3B8A, 0xFF23102E, 144,
                    "Six bands, one room, no overdubs.",
                    62, "https://sample.invalid/live-room.m3u8");
            ladder(liveRoom, "https://sample.invalid/live-room");
            genres(liveRoom, "documentary", "music");

            final Title firstTake = film("first-take", "First Take", 2025, 7.3f,
                    0xFF8A6A1F, 0xFF2E230B, 122,
                    "A session drummer's week at the end of a thirty-year career.",
                    96, "https://sample.invalid/first-take.m3u8");
            ladder(firstTake, "https://sample.invalid/first-take");
            genres(firstTake, "music", "drama");

            final List<Title> music = new ArrayList<>();
            music.add(liveRoom);
            music.add(firstTake);
            out.add(new Category("music", "Music", music));

            return out;
        }
    }
}
