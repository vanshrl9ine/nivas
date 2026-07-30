-- Locality reference data.
--
-- Seeded through a migration rather than a fixture script so every environment
-- (and every integration test) starts with the same location vocabulary. The
-- product is unusable without it: filters, facets and the geo bias all key off
-- these rows.
--
-- Coordinates are approximate locality centroids, good enough to centre a map
-- and to bias relevance.

INSERT INTO localities (slug, name, city, state, latitude, longitude) VALUES
    -- Bengaluru
    ('bengaluru-koramangala',       'Koramangala',        'Bengaluru', 'Karnataka', 12.9352, 77.6245),
    ('bengaluru-indiranagar',       'Indiranagar',        'Bengaluru', 'Karnataka', 12.9784, 77.6408),
    ('bengaluru-whitefield',        'Whitefield',         'Bengaluru', 'Karnataka', 12.9698, 77.7500),
    ('bengaluru-hsr-layout',        'HSR Layout',         'Bengaluru', 'Karnataka', 12.9116, 77.6474),
    ('bengaluru-jayanagar',         'Jayanagar',          'Bengaluru', 'Karnataka', 12.9250, 77.5938),
    ('bengaluru-electronic-city',   'Electronic City',    'Bengaluru', 'Karnataka', 12.8452, 77.6602),
    ('bengaluru-marathahalli',      'Marathahalli',       'Bengaluru', 'Karnataka', 12.9591, 77.6974),
    ('bengaluru-hebbal',            'Hebbal',             'Bengaluru', 'Karnataka', 13.0358, 77.5970),
    ('bengaluru-yelahanka',         'Yelahanka',          'Bengaluru', 'Karnataka', 13.1007, 77.5963),
    ('bengaluru-jp-nagar',          'JP Nagar',           'Bengaluru', 'Karnataka', 12.910,  77.5855),

    -- Mumbai
    ('mumbai-andheri-west',         'Andheri West',       'Mumbai',    'Maharashtra', 19.1364, 72.8296),
    ('mumbai-bandra-west',          'Bandra West',        'Mumbai',    'Maharashtra', 19.0596, 72.8295),
    ('mumbai-powai',                'Powai',              'Mumbai',    'Maharashtra', 19.1176, 72.9060),
    ('mumbai-thane-west',           'Thane West',         'Mumbai',    'Maharashtra', 19.2183, 72.9781),
    ('mumbai-lower-parel',          'Lower Parel',        'Mumbai',    'Maharashtra', 18.9960, 72.8302),
    ('mumbai-malad-west',           'Malad West',         'Mumbai',    'Maharashtra', 19.1860, 72.8484),

    -- Delhi NCR
    ('gurugram-dlf-phase-3',        'DLF Phase 3',        'Gurugram',  'Haryana',     28.4930, 77.0920),
    ('gurugram-sohna-road',         'Sohna Road',         'Gurugram',  'Haryana',     28.4089, 77.0378),
    ('noida-sector-62',             'Sector 62',          'Noida',     'Uttar Pradesh', 28.6270, 77.3720),
    ('noida-sector-137',            'Sector 137',         'Noida',     'Uttar Pradesh', 28.5065, 77.4016),
    ('delhi-saket',                 'Saket',              'Delhi',     'Delhi',       28.5245, 77.2066),
    ('delhi-dwarka',                'Dwarka',             'Delhi',     'Delhi',       28.5921, 77.0460),

    -- Hyderabad
    ('hyderabad-gachibowli',        'Gachibowli',         'Hyderabad', 'Telangana',   17.4401, 78.3489),
    ('hyderabad-hitech-city',       'HITEC City',         'Hyderabad', 'Telangana',   17.4435, 78.3772),
    ('hyderabad-kondapur',          'Kondapur',           'Hyderabad', 'Telangana',   17.4849, 78.3889),
    ('hyderabad-banjara-hills',     'Banjara Hills',      'Hyderabad', 'Telangana',   17.4126, 78.4392),

    -- Pune
    ('pune-hinjewadi',              'Hinjewadi',          'Pune',      'Maharashtra', 18.5912, 73.7389),
    ('pune-kharadi',                'Kharadi',            'Pune',      'Maharashtra', 18.5515, 73.9350),
    ('pune-baner',                  'Baner',              'Pune',      'Maharashtra', 18.5590, 73.7868),
    ('pune-viman-nagar',            'Viman Nagar',        'Pune',      'Maharashtra', 18.5679, 73.9143),

    -- Chennai
    ('chennai-omr',                 'OMR',                'Chennai',   'Tamil Nadu',  12.9010, 80.2279),
    ('chennai-adyar',               'Adyar',              'Chennai',   'Tamil Nadu',  13.0067, 80.2570),
    ('chennai-velachery',           'Velachery',          'Chennai',   'Tamil Nadu',  12.9791, 80.2209)
ON CONFLICT (slug) DO NOTHING;
