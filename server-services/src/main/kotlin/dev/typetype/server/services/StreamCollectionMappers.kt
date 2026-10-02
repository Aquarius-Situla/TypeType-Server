package dev.typetype.server.services

import dev.typetype.server.models.StreamCollectionEpisodeItem
import dev.typetype.server.models.StreamCollectionItem
import dev.typetype.server.models.StreamCollectionSectionItem
import org.schabi.newpipe.extractor.stream.StreamCollectionInfo

internal fun StreamCollectionInfo.toStreamCollectionItem(): StreamCollectionItem = StreamCollectionItem(
    id = id.orEmpty(),
    title = title.orEmpty(),
    sections = sections.orEmpty().map { section ->
        StreamCollectionSectionItem(
            id = section.id.orEmpty(),
            title = section.title.orEmpty(),
            episodes = section.episodes.orEmpty().map { episode ->
                StreamCollectionEpisodeItem(
                    videoId = episode.videoId.orEmpty(),
                    title = episode.title.orEmpty(),
                    url = episode.url.orEmpty(),
                )
            },
        )
    },
)
