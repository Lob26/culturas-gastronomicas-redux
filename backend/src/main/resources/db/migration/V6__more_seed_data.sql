-- =============================================================================
-- V6 — Amplía los datos de ejemplo hasta pasar de 10 filas en cada tabla.
--
-- Va en una migración NUEVA y no editando V2, y no es cuestión de estilo:
-- Flyway guarda un checksum de cada migración aplicada y `validate-on-migrate`
-- está activo, así que tocar V2 rompería el arranque en cualquier base donde ya
-- se hubiera aplicado, con un error de checksum que no dice que el archivo
-- cambió.
--
-- ── Sin un solo id explícito, al contrario que V2 ───────────────────────────
-- V2 corre sobre una base recién creada, donde los ids son predecibles. V6 no:
-- para cuando se aplica, la aplicación ya ha podido escribir. Cada ejecución
-- del end-to-end registra usuarios, sube imágenes y crea recetas, de modo que
-- los ids bajos están ocupados y un id fijo choca — como ocurrió al probar
-- esto: «duplicate key value violates unique constraint dish_multimedia_pkey».
--
-- Aquí todo se inserta dejando que la identidad asigne el id, y las relaciones
-- se resuelven por CLAVE NATURAL —slug, nombre, username— que sí es estable.
-- Sale más verboso y es inmune al estado previo de la base.
--
-- ── Lo que NO se añade ──────────────────────────────────────────────────────
-- Ninguna receta de pescado crudo ni de pasta italiana. El end-to-end fija que
-- «raw fish with seaweed» devuelva Sushi Rolls EL PRIMERO y que «creamy italian
-- pasta dish» devuelva la carbonara; un ceviche o unos linguine desplazarían
-- esos resultados y el test se pondría rojo por un cambio de datos, no por una
-- regresión del buscador.
-- =============================================================================

-- ------------------------------------------------------------------ países --
INSERT INTO country (name, iso2, iso3) VALUES
    ('España',    'ES', 'ESP'),
    ('Perú',      'PE', 'PER'),
    ('Tailandia', 'TH', 'THA'),
    ('Francia',   'FR', 'FRA'),
    ('Marruecos', 'MA', 'MAR'),
    ('Grecia',    'GR', 'GRC'),
    ('Vietnam',   'VN', 'VNM');

-- ---------------------------------------------------------------- culturas --
INSERT INTO gastronomic_culture (name, slug, description) VALUES
    ('Cocina española',   'cocina-espanola',   'Producto de temporada, brasa y arroces; mucha huerta y mucho mar.'),
    ('Cocina peruana',    'cocina-peruana',    'Mestizaje de raíces andinas con influencia japonesa y china.'),
    ('Cocina tailandesa', 'cocina-tailandesa', 'Equilibrio constante entre lo ácido, lo dulce, lo salado y lo picante.'),
    ('Cocina francesa',   'cocina-francesa',   'Técnica depurada y salsas como columna vertebral del plato.'),
    ('Cocina marroquí',   'cocina-marroqui',   'Guisos lentos en barro, con especias dulces y frutas secas.'),
    ('Cocina griega',     'cocina-griega',     'Aceite de oliva, hierbas secas y verduras de huerta.'),
    ('Cocina vietnamita', 'cocina-vietnamita', 'Caldos largos, hierbas frescas en crudo y muy poca grasa.');

INSERT INTO culture_country (culture_id, country_id)
SELECT c.id, p.id
FROM (VALUES
    ('cocina-espanola',   'España'),
    ('cocina-peruana',    'Perú'),
    ('cocina-tailandesa', 'Tailandia'),
    ('cocina-francesa',   'Francia'),
    ('cocina-marroqui',   'Marruecos'),
    ('cocina-griega',     'Grecia'),
    ('cocina-vietnamita', 'Vietnam'),
    -- La relación es N:M justamente para poder decir que una cocina se
    -- practica en más de un país.
    ('cocina-peruana',    'Colombia'),
    ('cocina-francesa',   'España'),
    ('cocina-griega',     'Francia')
) AS v(slug, pais)
JOIN gastronomic_culture c ON c.slug = v.slug
JOIN country p             ON p.name = v.pais;

-- -------------------------------------------------------------- categorías --
INSERT INTO gastronomic_category (name, culture_id)
SELECT v.nombre, c.id
FROM (VALUES
    ('Arroces',   'cocina-espanola'),
    ('Salteados', 'cocina-peruana'),
    ('Sopas',     'cocina-vietnamita'),
    ('Guisos',    'cocina-marroqui'),
    ('Horneados', 'cocina-griega'),
    ('Verduras',  'cocina-francesa'),
    ('Fideos',    'cocina-tailandesa')
) AS v(nombre, slug)
JOIN gastronomic_culture c ON c.slug = v.slug;

-- ----------------------------------------------------------------- recetas --
INSERT INTO recipe (name, slug, description, culture_id, prep_time_minutes, servings, difficulty)
SELECT v.nombre, v.slug, v.descripcion, c.id, v.minutos, v.raciones, v.dificultad
FROM (VALUES
    ('Paella Valenciana', 'paella-valenciana', 'Arroz seco de la huerta valenciana, con pollo, conejo y garrofón.', 'cocina-espanola',    90,  6::smallint, 'DIFICIL'),
    ('Lomo Saltado',      'lomo-saltado',      'Salteado peruano de ternera al wok con cebolla, tomate y patata frita.', 'cocina-peruana', 35,  4,          'MEDIA'),
    ('Pad Thai',          'pad-thai',          'Fideos de arroz salteados con tamarindo, cacahuete y lima.', 'cocina-tailandesa',        30,  2,          'MEDIA'),
    ('Ratatouille',       'ratatouille',       'Verduras de verano cocinadas por separado y reunidas al final.', 'cocina-francesa',      75,  4,          'FACIL'),
    ('Tajine de cordero', 'tajine-de-cordero', 'Guiso lento de cordero con ciruelas, almendra y canela.', 'cocina-marroqui',            150, 6,          'MEDIA'),
    ('Moussaka',          'moussaka',          'Capas de berenjena, carne especiada y bechamel, gratinadas al horno.', 'cocina-griega',  120, 8,          'DIFICIL'),
    ('Pho Bo',            'pho-bo',            'Caldo de ternera cocido durante horas, con fideos y hierbas en crudo.', 'cocina-vietnamita', 300, 4,       'DIFICIL')
) AS v(nombre, slug, descripcion, cultura, minutos, raciones, dificultad)
JOIN gastronomic_culture c ON c.slug = v.cultura;

-- ------------------------------------------------------------------- pasos --
INSERT INTO recipe_step (recipe_id, position, instruction, duration_seconds)
SELECT r.id, v.pos, v.texto, v.segundos
FROM (VALUES
    ('paella-valenciana', 1::smallint, 'Sofreír el pollo y el conejo troceados en la paellera con aceite de oliva hasta que estén dorados por todos lados.', 900),
    ('paella-valenciana', 2, 'Añadir el garrofón y la judía verde, rehogar unos minutos y agregar el tomate rallado y el pimentón.', 300),
    ('paella-valenciana', 3, 'Cubrir con agua, añadir el azafrán y cocer a fuego medio para hacer el caldo.', 2400),
    ('paella-valenciana', 4, 'Incorporar el arroz en cruz, repartirlo y no volver a removerlo. Cocer 18 minutos y dejar reposar tapado.', 1080),

    ('lomo-saltado', 1, 'Cortar la ternera en tiras gruesas y salpimentar. Calentar el wok hasta que humee.', NULL),
    ('lomo-saltado', 2, 'Sellar la carne en dos tandas para que no suelte agua y reservar.', 240),
    ('lomo-saltado', 3, 'Saltear la cebolla en gajos y el tomate, añadir vinagre y salsa de soja.', 300),
    ('lomo-saltado', 4, 'Devolver la carne al wok, añadir las patatas fritas y el cilantro, y saltear treinta segundos.', 30),

    ('pad-thai', 1, 'Hidratar los fideos de arroz en agua tibia hasta que estén flexibles pero firmes.', 1200),
    ('pad-thai', 2, 'Preparar la salsa mezclando pasta de tamarindo, azúcar de palma y salsa de pescado.', NULL),
    ('pad-thai', 3, 'Saltear el ajo y el tofu, añadir el huevo y revolverlo.', 180),
    ('pad-thai', 4, 'Incorporar los fideos escurridos y la salsa, saltear y terminar con cacahuete, brotes y lima.', 240),

    ('ratatouille', 1, 'Cortar berenjena, calabacín, pimiento y cebolla en dados del mismo tamaño.', NULL),
    ('ratatouille', 2, 'Cocinar cada verdura POR SEPARADO en aceite de oliva; cada una tiene su punto y juntas se aguan.', 1800),
    ('ratatouille', 3, 'Preparar un sofrito de tomate con ajo y hierbas de Provenza.', 900),
    ('ratatouille', 4, 'Reunir todo, corregir de sal y dejar reposar; al día siguiente está mejor.', 600),

    ('tajine-de-cordero', 1, 'Dorar el cordero en la olla de barro con aceite, cebolla y las especias.', 900),
    ('tajine-de-cordero', 2, 'Añadir agua hasta cubrir a media altura y cocer tapado a fuego muy suave.', 5400),
    ('tajine-de-cordero', 3, 'Incorporar las ciruelas y la canela en la última media hora.', 1800),
    ('tajine-de-cordero', 4, 'Tostar la almendra aparte y esparcirla por encima al servir.', 300),

    ('moussaka', 1, 'Cortar la berenjena en láminas, salarla y dejarla escurrir para que pierda el amargor.', 1800),
    ('moussaka', 2, 'Asar las láminas hasta que estén tiernas y doradas.', 1500),
    ('moussaka', 3, 'Preparar la carne picada con cebolla, tomate, canela y orégano.', 1800),
    ('moussaka', 4, 'Hacer una bechamel espesa y montar por capas: berenjena, carne, berenjena, bechamel.', 900),
    ('moussaka', 5, 'Hornear a 180 °C hasta que la superficie esté dorada y dejar templar antes de cortar.', 2700),

    ('pho-bo', 1, 'Blanquear los huesos de ternera un par de minutos y desechar esa primera agua.', 300),
    ('pho-bo', 2, 'Tostar la cebolla y el jengibre enteros directamente sobre el fuego hasta que se chamusquen.', 600),
    ('pho-bo', 3, 'Cocer los huesos con la cebolla, el jengibre, el anís estrellado y la canela a fuego mínimo.', 18000),
    ('pho-bo', 4, 'Colar el caldo, salarlo y servirlo hirviendo sobre los fideos y la carne cruda en láminas.', NULL),
    ('pho-bo', 5, 'Llevar a la mesa la albahaca, los brotes y la lima aparte, para que cada uno los añada.', NULL)
) AS v(slug, pos, texto, segundos)
JOIN recipe r ON r.slug = v.slug;

-- ------------------------------------------------------------ ingredientes --
INSERT INTO ingredient (recipe_id, position, name, quantity, unit)
SELECT r.id, v.pos, v.nombre, v.cantidad, v.unidad
FROM (VALUES
    ('paella-valenciana', 1::smallint, 'Arroz bomba',      400::numeric, 'g'),
    ('paella-valenciana', 2, 'Pollo troceado',   500, 'g'),
    ('paella-valenciana', 3, 'Conejo troceado',  400, 'g'),
    ('paella-valenciana', 4, 'Garrofón',         150, 'g'),
    ('paella-valenciana', 5, 'Azafrán',            1, 'pizca'),

    ('lomo-saltado', 1, 'Lomo de ternera', 500, 'g'),
    ('lomo-saltado', 2, 'Cebolla roja',      2, 'unidad'),
    ('lomo-saltado', 3, 'Salsa de soja',    30, 'ml'),
    ('lomo-saltado', 4, 'Patatas',         400, 'g'),

    ('pad-thai', 1, 'Fideos de arroz',    200, 'g'),
    ('pad-thai', 2, 'Pasta de tamarindo',  40, 'g'),
    ('pad-thai', 3, 'Cacahuete tostado',   50, 'g'),
    ('pad-thai', 4, 'Lima',                 1, 'unidad'),

    ('ratatouille', 1, 'Berenjena',        2, 'unidad'),
    ('ratatouille', 2, 'Calabacín',        2, 'unidad'),
    ('ratatouille', 3, 'Pimiento rojo',    1, 'unidad'),
    ('ratatouille', 4, 'Tomate maduro',  500, 'g'),

    ('tajine-de-cordero', 1, 'Cordero',        1.2, 'kg'),
    ('tajine-de-cordero', 2, 'Ciruelas pasas', 200, 'g'),
    ('tajine-de-cordero', 3, 'Almendra cruda', 100, 'g'),
    ('tajine-de-cordero', 4, 'Canela en rama',   1, 'unidad'),

    ('moussaka', 1, 'Berenjena',          3, 'unidad'),
    ('moussaka', 2, 'Carne picada',     600, 'g'),
    ('moussaka', 3, 'Leche entera',     750, 'ml'),
    ('moussaka', 4, 'Queso kefalotyri', 100, 'g'),

    ('pho-bo', 1, 'Huesos de ternera',   2, 'kg'),
    ('pho-bo', 2, 'Fideos de arroz',   400, 'g'),
    ('pho-bo', 3, 'Anís estrellado',     4, 'unidad'),
    ('pho-bo', 4, 'Jengibre',          100, 'g')
) AS v(slug, pos, nombre, cantidad, unidad)
JOIN recipe r ON r.slug = v.slug;

-- ---------------------------------------------------------------- imágenes --
-- Direcciones plausibles pero inventadas: el verificador de enlaces las dará
-- por rotas, que es justo lo que se quiere ejercitar. La malformada de V2
-- sigue siendo la fixture del caso raro.
INSERT INTO dish_multimedia (recipe_id, url, position)
SELECT r.id, v.url, v.pos
FROM (VALUES
    ('paella-valenciana', 'https://ejemplo.invalid/imagenes/paella-valenciana.jpg', 1::smallint),
    ('lomo-saltado',      'https://ejemplo.invalid/imagenes/lomo-saltado.jpg', 1),
    ('pad-thai',          'https://ejemplo.invalid/imagenes/pad-thai.jpg', 1),
    ('ratatouille',       'https://ejemplo.invalid/imagenes/ratatouille.jpg', 1),
    ('tajine-de-cordero', 'https://ejemplo.invalid/imagenes/tajine.jpg', 1),
    ('moussaka',          'https://ejemplo.invalid/imagenes/moussaka.jpg', 1),
    ('pho-bo',            'https://ejemplo.invalid/imagenes/pho-bo.jpg', 1),
    ('pho-bo',            'https://ejemplo.invalid/imagenes/pho-bo-detalle.jpg', 2)
) AS v(slug, url, pos)
JOIN recipe r ON r.slug = v.slug;

-- ------------------------------------------ productos representativos --
INSERT INTO representative_product (name, brand, category_id)
SELECT v.nombre, v.marca, c.id
FROM (VALUES
    ('Arroz bomba D.O.',      'Tartana',      'Arroces'),
    ('Ají amarillo en pasta', 'Doña Isabel',  'Salteados'),
    ('Salsa de pescado',      'Squid Brand',  'Fideos'),
    ('Pasta de tamarindo',    'Cock Brand',   'Fideos'),
    ('Ras el hanout',         'Dar Zaman',    'Guisos'),
    ('Aceite de oliva',       'Terra Creta',  'Horneados'),
    ('Salsa hoisin',          'Lee Kum Kee',  'Sopas')
) AS v(nombre, marca, categoria)
JOIN gastronomic_category c ON c.name = v.categoria;

-- ------------------------------------------------------------ restaurantes --
INSERT INTO restaurant (name, city, contact, country_id)
SELECT v.nombre, v.ciudad, v.contacto, p.id
FROM (VALUES
    ('Casa Carmela',    'Valencia',  'reservas@casacarmela.invalid', 'España'),
    ('Central',         'Lima',      'contacto@central.invalid',     'Perú'),
    ('Nahm',            'Bangkok',   'hola@nahm.invalid',            'Tailandia'),
    ('Le Petit Jardin', 'Lyon',      'bonjour@petitjardin.invalid',  'Francia'),
    ('Dar Yacout',      'Marrakech', 'contacto@daryacout.invalid',   'Marruecos'),
    ('To Kati Allo',    'Atenas',    'info@katiallo.invalid',        'Grecia'),
    ('Pho Gia Truyen',  'Hanói',     'contacto@phogia.invalid',      'Vietnam')
) AS v(nombre, ciudad, contacto, pais)
JOIN country p ON p.name = v.pais;

-- -------------------------------------------------------------- estrellas --
-- El trigger impone 0..3 por restaurante y es DEFERRABLE, así que la cuenta se
-- comprueba al confirmar la migración entera. Ninguno pasa de tres.
--
-- Las fechas de La Taquería evitan a propósito 2001-01-01..03 y 2020-05-05:
-- son las que usa SchemaAndConstraintsIT para llevar el primer restaurante al
-- límite y comprobar que la cuarta falla. Coincidir rompería su UNIQUE
-- (restaurant_id, acquired) y el test fallaría por la razón equivocada.
INSERT INTO michelin_star (restaurant_id, acquired)
SELECT r.id, v.fecha
FROM (VALUES
    ('La Taquería',   DATE '2018-03-12'),
    ('La Taquería',   DATE '2019-03-11'),
    ('La Pizzeria',   DATE '2021-04-05'),
    ('Curry House',   DATE '2022-02-14'),
    ('Central',       DATE '2017-06-01'),
    ('Central',       DATE '2019-06-03'),
    ('Central',       DATE '2023-06-05'),
    ('Nahm',          DATE '2020-09-21'),
    ('To Kati Allo',  DATE '2016-05-30')
) AS v(restaurante, fecha)
JOIN restaurant r ON r.name = v.restaurante;

-- --------------------------------------------------- recetas y restaurantes --
INSERT INTO recipe_restaurant (recipe_id, restaurant_id)
SELECT r.id, s.id
FROM (VALUES
    ('paella-valenciana', 'Casa Carmela'),
    ('lomo-saltado',      'Central'),
    ('pad-thai',          'Nahm'),
    ('ratatouille',       'Le Petit Jardin'),
    ('tajine-de-cordero', 'Dar Yacout'),
    ('moussaka',          'To Kati Allo'),
    ('pho-bo',            'Pho Gia Truyen'),
    -- Un restaurante sirve más de una receta y al revés.
    ('ratatouille',       'Casa Carmela'),
    ('pho-bo',            'Nahm')
) AS v(slug, restaurante)
JOIN recipe r     ON r.slug = v.slug
JOIN restaurant s ON s.name = v.restaurante;

-- ---------------------------------------------------------------- usuarios --
-- Todos comparten el hash de 'demo1234', el mismo de la cuenta demo de V3.
-- Aceptable SÓLO por ser datos de ejemplo de un entorno local: once hashes
-- distintos no añadirían nada, y ninguna de estas cuentas existe fuera de aquí.
INSERT INTO app_user (username, email, display_name, password_hash, role) VALUES
    ('ana.torres',    'ana@culturas.local',    'Ana Torres',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('bruno.diaz',    'bruno@culturas.local',  'Bruno Díaz',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('carla.mena',    'carla@culturas.local',  'Carla Mena',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('diego.rojas',   'diego@culturas.local',  'Diego Rojas',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('elena.vargas',  'elena@culturas.local',  'Elena Vargas',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('felipe.nunez',  'felipe@culturas.local', 'Felipe Núñez',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('gabriela.sosa', 'gabi@culturas.local',   'Gabriela Sosa', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('hugo.medina',   'hugo@culturas.local',   'Hugo Medina',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('irene.pardo',   'irene@culturas.local',  'Irene Pardo',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('javier.luna',   'javier@culturas.local', 'Javier Luna',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'USER'),
    ('karen.ortiz',   'karen@culturas.local',  'Karen Ortiz',   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'ADMIN');

-- ------------------------------------------------------------- valoraciones --
-- El trigger de V3 recalcula rating_average y rating_count de cada receta
-- dentro de esta misma transacción; no hace falta escribirlos a mano.
INSERT INTO rating (user_id, recipe_id, score, comment)
SELECT u.id, r.id, v.puntuacion, v.comentario
FROM (VALUES
    ('ana.torres',    'tacos-al-pastor',   5::smallint, 'La marinada de achiote merece la espera.'),
    ('bruno.diaz',    'tacos-al-pastor',   4, 'Muy buenos, aunque la piña no es para todo el mundo.'),
    ('carla.mena',    'pasta-carbonara',   5, 'Sin nata, por fin una receta que lo dice claro.'),
    ('diego.rojas',   'pasta-carbonara',   4, 'El punto del huevo cuesta cogerle el truco.'),
    ('elena.vargas',  'sushi-rolls',       5, 'El arroz quedó en su punto siguiendo los tiempos.'),
    ('felipe.nunez',  'chicken-curry',     4, 'Aromático de verdad si tuestas las especias.'),
    ('gabriela.sosa', 'bandeja-paisa',     5, 'Contundente, como debe ser.'),
    ('hugo.medina',   'paella-valenciana', 5, 'El socarrat salió a la primera.'),
    ('irene.pardo',   'paella-valenciana', 4, 'Difícil pero merece la pena.'),
    ('javier.luna',   'lomo-saltado',      5, 'El wok bien caliente cambia el plato por completo.'),
    ('karen.ortiz',   'pad-thai',          4, 'Justo de picante, se agradece.'),
    ('ana.torres',    'ratatouille',       5, 'Cocinar cada verdura aparte marca la diferencia.'),
    ('bruno.diaz',    'tajine-de-cordero', 4, 'Las ciruelas al final, no antes.'),
    ('carla.mena',    'moussaka',          5, 'La bechamel espesa es la clave.'),
    ('diego.rojas',   'pho-bo',            5, 'Cinco horas de caldo bien empleadas.'),
    ('elena.vargas',  'pho-bo',            4, 'Largo de hacer, buenísimo de comer.'),
    ('felipe.nunez',  'ratatouille',       3, 'Correcto, pero le falta algo de sal.')
) AS v(usuario, slug, puntuacion, comentario)
JOIN app_user u ON u.username = v.usuario
JOIN recipe r   ON r.slug     = v.slug;

-- ---------------------------------------------------------------- favoritos --
INSERT INTO favorite (user_id, target_type, target_id)
SELECT u.id, v.tipo, v.destino
FROM (VALUES
    ('ana.torres',    'RECIPE',     (SELECT id FROM recipe WHERE slug = 'pasta-carbonara')),
    ('ana.torres',    'CULTURE',    (SELECT id FROM gastronomic_culture WHERE slug = 'cocina-italiana')),
    ('bruno.diaz',    'RECIPE',     (SELECT id FROM recipe WHERE slug = 'paella-valenciana')),
    ('carla.mena',    'RECIPE',     (SELECT id FROM recipe WHERE slug = 'moussaka')),
    ('diego.rojas',   'RECIPE',     (SELECT id FROM recipe WHERE slug = 'pho-bo')),
    ('elena.vargas',  'CULTURE',    (SELECT id FROM gastronomic_culture WHERE slug = 'cocina-peruana')),
    ('felipe.nunez',  'RECIPE',     (SELECT id FROM recipe WHERE slug = 'chicken-curry')),
    ('gabriela.sosa', 'RESTAURANT', (SELECT id FROM restaurant WHERE name = 'Central')),
    ('hugo.medina',   'RECIPE',     (SELECT id FROM recipe WHERE slug = 'paella-valenciana')),
    ('irene.pardo',   'CULTURE',    (SELECT id FROM gastronomic_culture WHERE slug = 'cocina-vietnamita')),
    ('javier.luna',   'RECIPE',     (SELECT id FROM recipe WHERE slug = 'lomo-saltado')),
    ('karen.ortiz',   'RESTAURANT', (SELECT id FROM restaurant WHERE name = 'Sushi Paradise'))
) AS v(usuario, tipo, destino)
JOIN app_user u ON u.username = v.usuario;

-- Sin `setval`: al no fijar ningún id, las secuencias de identidad avanzan
-- solas y siguen coherentes. Era V2 quien lo necesitaba, por insertar ids
-- explícitos sobre una base recién creada.
