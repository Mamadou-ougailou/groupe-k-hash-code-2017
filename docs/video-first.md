video_sizes[video_id]
endpoint_datacenter_latency[endpoint_id]
endpoint_to_caches[endpoint_id] -> [(cache_id, latence), ...]
video_to_endpoints_requesting[video_id] -> [(endpoint_id, nb_requetes), ...]
cache_capacity_used[cache_id]                   # initialisé à 0
best_latency_so_far[(endpoint_id, video_id)]    # initialisé à endpoint_datacenter_latency[endpoint_id]


FONCTION calculer_score_video(video):
    score = 0
    POUR CHAQUE (endpoint, nb_requetes) DANS video_to_endpoints_requesting[video]:
        meilleure_latence_possible = MIN(latence DE endpoint_to_caches[endpoint])
        gain = max(0, best_latency_so_far[(endpoint, video)] - meilleure_latence_possible)
        score += nb_requetes * gain
    RETOURNER score / video_sizes[video]


FONCTION calculer_gain_cache_pour_video(cache, video, endpoints_deja_servis):
    gain = 0
    POUR CHAQUE (endpoint, nb_requetes) DANS video_to_endpoints_requesting[video]:
        SI endpoint DANS endpoints_deja_servis:
            CONTINUER  # déjà bien servi par un cache placé précédemment pour cette vidéo
        SI cache N'EST PAS DANS endpoint_to_caches[endpoint]:
            CONTINUER  # cache pas accessible à cet endpoint
        latence_cache = latence(endpoint, cache)
        g = max(0, best_latency_so_far[(endpoint, video)] - latence_cache)
        gain += nb_requetes * g
    RETOURNER gain


ALGO PRINCIPAL:

    # 1. Initialisation
    POUR CHAQUE (endpoint, video) possible:
        best_latency_so_far[(endpoint, video)] = endpoint_datacenter_latency[endpoint]

    # 2. Ordonner les vidéos par score décroissant (calculé une fois, au début)
    videos_ordonnees = TRIER(toutes_les_videos, PAR calculer_score_video(video) DECROISSANT)

    # 3. Traiter chaque vidéo dans cet ordre
    POUR CHAQUE video DANS videos_ordonnees:

        # a. Construire la liste des caches candidats pour cette vidéo
        caches_candidats = ENSEMBLE_VIDE()
        POUR CHAQUE (endpoint, _) DANS video_to_endpoints_requesting[video]:
            POUR CHAQUE (cache, _) DANS endpoint_to_caches[endpoint]:
                caches_candidats.ajouter(cache)

        endpoints_deja_servis = ENSEMBLE_VIDE()

        # b. Placer la vidéo dans les caches, un par un, tant qu'il y a du gain
        TANT QUE VRAI:
            meilleur_cache = NULL
            meilleur_gain = 0

            POUR CHAQUE cache DANS caches_candidats:
                SI cache_capacity_used[cache] + video_sizes[video] > X:
                    CONTINUER  # pas de place
                gain = calculer_gain_cache_pour_video(cache, video, endpoints_deja_servis)
                SI gain > meilleur_gain:
                    meilleur_gain = gain
                    meilleur_cache = cache

            SI meilleur_cache == NULL:
                SORTIR DE LA BOUCLE  # plus aucun gain possible pour cette vidéo

            # Placer la vidéo dans le meilleur cache trouvé
            cache_content[meilleur_cache].ajouter(video)
            cache_capacity_used[meilleur_cache] += video_sizes[video]
            caches_candidats.retirer(meilleur_cache)

            # Mettre à jour l'état pour les endpoints concernés
            POUR CHAQUE (endpoint, nb_requetes) DANS video_to_endpoints_requesting[video]:
                SI meilleur_cache DANS endpoint_to_caches[endpoint]:
                    latence_cache = latence(endpoint, meilleur_cache)
                    SI latence_cache < best_latency_so_far[(endpoint, video)]:
                        best_latency_so_far[(endpoint, video)] = latence_cache
                    endpoints_deja_servis.ajouter(endpoint)

    # 4. Générer la sortie
    POUR CHAQUE cache:
        AFFICHER cache_id, cache_content[cache]