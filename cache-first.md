video_sizes[video_id]                          # taille de chaque vidéo
endpoint_datacenter_latency[endpoint_id]        # latence vers le datacenter
cache_to_endpoints[cache_id] -> [(endpoint_id, latence), ...]
endpoint_to_videos_requested[endpoint_id] -> [(video_id, nb_requetes), ...]
cache_capacity_used[cache_id]                   # initialisé à 0
best_latency_so_far[(endpoint_id, video_id)]    # initialisé à endpoint_datacenter_latency[endpoint_id]


FONCTION calculer_importance_cache(cache):
    importance = 0
    POUR CHAQUE (endpoint, latence_cache) DANS cache_to_endpoints[cache]:
        POUR CHAQUE (video, nb_requetes) DANS endpoint_to_videos_requested[endpoint]:
            gain = max(0, best_latency_so_far[(endpoint, video)] - latence_cache)
            importance += nb_requetes * gain
    RETOURNER importance


FONCTION calculer_valeur_video_pour_cache(video, cache):
    valeur = 0
    POUR CHAQUE (endpoint, latence_cache) DANS cache_to_endpoints[cache]:
        SI (endpoint, video) est demandé PAR endpoint:
            nb_requetes = endpoint_to_videos_requested[endpoint][video]
            gain = max(0, best_latency_so_far[(endpoint, video)] - latence_cache)
            valeur += nb_requetes * gain
    RETOURNER valeur


ALGO PRINCIPAL:

    # 1. Initialisation
    POUR CHAQUE (endpoint, video) possible:
        best_latency_so_far[(endpoint, video)] = endpoint_datacenter_latency[endpoint]

    # 2. Ordonner les caches par importance décroissante
    caches_ordonnes = TRIER(tous_les_caches, PAR calculer_importance_cache(cache) DECROISSANT)

    # 3. Traiter chaque cache dans cet ordre
    POUR CHAQUE cache DANS caches_ordonnes:

        # a. Construire la liste des vidéos candidates pour ce cache
        videos_candidates = ENSEMBLE_VIDE()
        POUR CHAQUE (endpoint, _) DANS cache_to_endpoints[cache]:
            POUR CHAQUE (video, _) DANS endpoint_to_videos_requested[endpoint]:
                videos_candidates.ajouter(video)

        # b. Calculer le ratio valeur/taille pour chaque vidéo candidate
        scores = []
        POUR CHAQUE video DANS videos_candidates:
            valeur = calculer_valeur_video_pour_cache(video, cache)
            SI valeur > 0:
                ratio = valeur / video_sizes[video]
                scores.ajouter((video, ratio))

        # c. Trier par ratio décroissant
        scores_tries = TRIER(scores, PAR ratio DECROISSANT)

        # d. Remplir le cache glouton (knapsack par ratio)
        POUR CHAQUE (video, ratio) DANS scores_tries:
            SI cache_capacity_used[cache] + video_sizes[video] <= X:
                # Placer la vidéo dans le cache
                cache_content[cache].ajouter(video)
                cache_capacity_used[cache] += video_sizes[video]

                # Mettre à jour l'état pour tous les endpoints concernés
                POUR CHAQUE (endpoint, latence_cache) DANS cache_to_endpoints[cache]:
                    SI (endpoint, video) est demandé:
                        SI latence_cache < best_latency_so_far[(endpoint, video)]:
                            best_latency_so_far[(endpoint, video)] = latence_cache

    # 4. Générer la sortie
    POUR CHAQUE cache:
        AFFICHER cache_id, cache_content[cache]