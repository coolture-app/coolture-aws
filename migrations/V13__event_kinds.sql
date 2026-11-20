-- Kinds of event: formats such as concerts or workshops, beside the V9 interests (topics). Users pick kinds in
-- onboarding, the post-embedding worker derives each post's kinds, and the feed filters by them.
-- V9's interests keep their texts and vectors: the survey's answers were given on them. Medytacja's group is the
-- one change, below.

CREATE TABLE event_kinds (
    key VARCHAR(64) PRIMARY KEY,
    label_pl VARCHAR(128) NOT NULL,
    label_en VARCHAR(128) NOT NULL,
    position INTEGER NOT NULL,
    embedding_texts TEXT[] NOT NULL CHECK (cardinality(embedding_texts) > 0)
);

-- One vector per kind, the normalised mean of its texts' vectors. embedded_texts records what was embedded, so editing
-- embedding_texts triggers a re-embed.
CREATE TABLE event_kind_embeddings (
    kind_key VARCHAR(64) PRIMARY KEY REFERENCES event_kinds(key) ON DELETE CASCADE,
    embedding vector(768) NOT NULL,
    model VARCHAR(128) NOT NULL,
    embedded_texts TEXT[] NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_kinds (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind_key VARCHAR(64) NOT NULL REFERENCES event_kinds(key),
    PRIMARY KEY (user_id, kind_key)
);

-- Written by the post-embedding worker, best kind first.
CREATE TABLE rec_post_kinds (
    post_id UUID PRIMARY KEY REFERENCES posts(id) ON DELETE CASCADE,
    kinds VARCHAR(64)[] NOT NULL CHECK (cardinality(kinds) > 0),
    model VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Topics leave onboarding but keep their rows, vectors and holders, since the survey stores respondents' picks as these
-- keys: those with no event behind them, and those named like a kind, which the kind covers.
ALTER TABLE interests ADD COLUMN offered BOOLEAN NOT NULL DEFAULT true;
UPDATE interests SET offered = false WHERE key IN ('true_crime', 'series', 'crime_novels', 'road_trips', 'camping', 'cats', 'minimalism', 'astrology', 'spirituality');
UPDATE interests SET offered = false WHERE key IN ('concerts', 'cinema', 'parties', 'wellness');

-- The Sport group shares its kind's name. Medytacja joins it beside Joga and Pilates: with Wellness hidden it was the
-- only topic Styl życia offered. Not only a label: a pick adds 0.25 of its group's mean, so the move changes the taste
-- of anyone holding a topic in either group.
UPDATE interest_categories SET label_pl = 'Aktywność i sport', label_en = 'Activity and sport' WHERE key = 'sports';
UPDATE interests SET category_key = 'sports', position = 3 WHERE key = 'meditation';

INSERT INTO event_kinds (key, label_pl, label_en, position, embedding_texts) VALUES
    ('concerts', 'Koncerty', 'Concerts', 0, ARRAY[
        'Koncert: występ zespołu lub wokalisty na żywo w klubie, hali albo w plenerze, trasa koncertowa i przeboje z nowej płyty.',
        'Koncert muzyki klasycznej: filharmonia, orkiestra symfoniczna, recital fortepianowy, kwartet smyczkowy i chór.',
        'Koncert jazzowy, bluesowy lub folkowy w klubie muzycznym, kameralny występ akustyczny.',
        'Festiwal muzyczny: kilka scen, line-up artystów, koncerty rockowe, popowe, hip-hopowe i metalowe.']),
    ('stage', 'Scena', 'Stage', 1, ARRAY[
        'Spektakl teatralny: przedstawienie w teatrze, aktorzy na scenie, premiera sztuki w reżyserii.',
        'Stand-up i kabaret: występ komika, nowy program, skecze i wieczór komediowy na scenie.',
        'Opera, operetka, balet i musical: przedstawienie z orkiestrą, śpiewakami i tancerzami.',
        'Spektakl taneczny, performans, teatr lalek, monodram i nowy cyrk.']),
    ('cinema', 'Kino', 'Cinema', 2, ARRAY[
        'Seans filmowy w kinie: premiera filmu, pokaz przedpremierowy, bilety na film.',
        'Festiwal filmowy i przegląd filmów: pokazy konkursowe, retrospektywa reżysera, kino studyjne.',
        'Pokaz filmu ze spotkaniem z reżyserem lub dyskusją po seansie, dyskusyjny klub filmowy.',
        'Kino plenerowe, maraton filmowy, pokaz filmu dokumentalnego lub animowanego.']),
    ('exhibitions', 'Wystawy', 'Exhibitions', 3, ARRAY[
        'Wystawa w galerii: obrazy, rzeźby, instalacje, sztuka współczesna.',
        'Wystawa w muzeum: zbiory, eksponaty, ekspozycja historyczna, zwiedzanie wystawy stałej.',
        'Wystawa czasowa fotografii, plakatu, grafiki lub designu.',
        'Wernisaż i finisaż: otwarcie wystawy z udziałem artysty, oprowadzanie kuratorskie.']),
    ('talks', 'Spotkania i wykłady', 'Talks', 4, ARRAY[
        'Wykład: prelegent opowiada o nauce, historii lub kulturze, wstęp wolny.',
        'Spotkanie autorskie: pisarz o swojej nowej książce, rozmowa z autorem, podpisywanie książek.',
        'Debata i panel dyskusyjny: rozmowa z ekspertami i pytania z sali.',
        'Konferencja i meetup: prezentacje i prelekcje dla zainteresowanych tematem.']),
    ('workshops', 'Warsztaty', 'Workshops', 5, ARRAY[
        'Warsztaty: zajęcia praktyczne w małej grupie z prowadzącym, zapisy, materiały w cenie.',
        'Warsztaty plastyczne i rękodzieła: malowanie, ceramika, szycie, robienie ozdób.',
        'Kurs i zajęcia otwarte: nauka tańca, fotografii, pisania lub gry na instrumencie.',
        'Warsztaty dla dorosłych: kaligrafia, florystyka, stolarka, szycie lub fotografia od podstaw.']),
    ('nightlife', 'Imprezy', 'Parties', 6, ARRAY[
        'Impreza w klubie: DJ, parkiet, taniec do rana, muzyka elektroniczna lub hity lat 80. i 90.',
        'Impreza tematyczna: andrzejki, sylwester, bal, potańcówka, disco.',
        'Wieczór w pubie lub barze: karaoke, muzyka, drinki, wieczór towarzyski.',
        'Wieczór tańca: salsa, bachata, swing, social dance dla par.']),
    ('fairs', 'Festyny i targi', 'Fairs', 7, ARRAY[
        'Targi i kiermasz: wystawcy, stoiska, rękodzieło, jarmark świąteczny.',
        'Festyn i piknik miejski: dni miasta, atrakcje dla całej rodziny, scena plenerowa.',
        'Pchli targ, giełda staroci, kiermasz płyt, książek lub roślin.',
        'Targi branżowe i konwent: stoiska, wystawcy, nowości, spotkania z twórcami.']),
    ('food', 'Kulinaria', 'Food', 8, ARRAY[
        'Degustacja wina, piwa kraftowego, kawy lub serów z sommelierem lub piwowarem.',
        'Kolacja degustacyjna w restauracji: menu szefa kuchni, kilka dań, kuchnie świata.',
        'Festiwal jedzenia: food trucki, street food, smaki regionu.',
        'Warsztaty kulinarne: gotowanie, pieczenie, wspólne przygotowywanie potraw.']),
    ('sports', 'Aktywność i sport', 'Activity and sport', 9, ARRAY[
        'Mecz: piłka nożna, siatkówka, koszykówka, hokej, kibicowanie na stadionie lub w hali.',
        'Bieg uliczny, maraton, zawody sportowe, zapisy dla uczestników, pakiet startowy.',
        'Trening dla amatorów: fitness, crossfit, bieganie z trenerem, zajęcia sportowe w hali lub w plenerze.',
        'Gala sportów walki, turniej, zawody pływackie, kolarskie lub żeglarskie.']),
    ('wellbeing', 'Wellness i zdrowie', 'Wellness', 10, ARRAY[
        'Wellness: ceremonie saunowe, spa, masaże i dzień relaksu w strefie odnowy.',
        'Kąpiel w dźwiękach: misy tybetańskie, gongi, koncert relaksacyjny i medytacja prowadzona.',
        'Joga, pilates i praca z oddechem: zajęcia dla ciała i spokoju umysłu.',
        'Zdrowie: profilaktyka, badania, rozmowa z lekarzem lub psychologiem, dzień zdrowia.']),
    ('outdoors', 'Spacery i wycieczki', 'Walks and trips', 11, ARRAY[
        'Spacer z przewodnikiem po mieście: historia dzielnicy, zabytki, kamienice.',
        'Wycieczka: wyjazd autokarem, zwiedzanie okolicy, rejs, wędrówka po szlaku.',
        'Spacer przyrodniczy: las, park, plaża, obserwacja ptaków, nordic walking.',
        'Zwiedzanie z przewodnikiem: zabytkowy budynek, fort, latarnia, miejsca na co dzień zamknięte.']),
    ('kids', 'Dla dzieci', 'For kids', 12, ARRAY[
        'Dla dzieci: spektakl dla najmłodszych, bajka, teatrzyk, przedstawienie dla dzieci.',
        'Warsztaty dla dzieci: zajęcia plastyczne, naukowe i ruchowe dla przedszkolaków i uczniów.',
        'Wydarzenie rodzinne: atrakcje dla dzieci i rodziców, animacje, mikołajki, piknik rodzinny.',
        'Seans dla dzieci, film animowany, kino familijne, zajęcia dla maluchów z rodzicami.']),
    ('games', 'Gry', 'Games', 13, ARRAY[
        'Wieczór gier planszowych: planszówki, karcianki, nauka zasad, klub gier.',
        'Turniej: szachy, e-sport, gry wideo, turniej gier karcianych.',
        'Quiz w pubie, teleturniej drużynowy, escape room, gra miejska.',
        'Sesja RPG, larp, konwent fantastyki i gier.']),
    ('causes', 'Akcje społeczne', 'Causes', 14, ARRAY[
        'Akcja charytatywna: zbiórka pieniędzy, licytacje, koncert lub bieg na rzecz potrzebujących.',
        'Wolontariat: sprzątanie świata, pomoc w schronisku, bank żywności, zbiórka darów.',
        'Marsz, manifestacja, protest, parada równości, wydarzenie obywatelskie.',
        'Zbiórka krwi, akcja ekologiczna, wymiana ubrań, sadzenie drzew, spotkanie sąsiedzkie.']);
