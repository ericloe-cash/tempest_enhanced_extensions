/*
 * Copyright 2021 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.cash.tempest2

import app.cash.tempest2.extensions.TempestAutoGeneratedTimestampRecordExtension
import app.cash.tempest2.extensions.WithResultExtension
import app.cash.tempest2.musiclibrary.AlbumTrack
import app.cash.tempest2.musiclibrary.MusicDb
import app.cash.tempest2.musiclibrary.PlaylistInfo
import app.cash.tempest2.musiclibrary.testDb
import app.cash.tempest2.musiclibrary.versionedattribute.VersionedAttribute
import app.cash.tempest2.musiclibrary.versionedattribute.VersionedAttributeDb
import app.cash.tempest2.testing.logicalDb
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import software.amazon.awssdk.enhanced.dynamodb.Expression
import software.amazon.awssdk.enhanced.dynamodb.extensions.AutoGeneratedTimestampRecordExtension
import software.amazon.awssdk.enhanced.dynamodb.internal.client.ExtensionResolver.defaultExtensions
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class LogicalDbTransactionTest {

  @RegisterExtension
  @JvmField
  val db = testDb()

  private val musicDb by lazy { db.logicalDb<MusicDb>() }
  private val musicTable by lazy { musicDb.music }

  private val fakeClock = FakeClock(tickOnNow = 1.minutes)

  private val autoGeneratedTimestampRecordExtension =
    AutoGeneratedTimestampRecordExtension.builder().baseClock(fakeClock).build()

  private val tempestAutoGeneratedTimestampRecordExtension =
    TempestAutoGeneratedTimestampRecordExtension.create(fakeClock)

  private val versionedAttributeDb by lazy {
    db.logicalDb<VersionedAttributeDb>(
      defaultExtensions() + autoGeneratedTimestampRecordExtension + tempestAutoGeneratedTimestampRecordExtension + WithResultExtension.create()
    )
  }

  @Test
  fun transactionLoad() {
    val albumTracks = listOf(
      AlbumTrack(
        "ALBUM_1",
        1,
        "dreamin'",
        Duration.parse("PT3M28S")
      ),
      AlbumTrack(
        "ALBUM_1",
        2,
        "what you do to me",
        Duration.parse("PT3M24S")
      ),
      AlbumTrack(
        "ALBUM_1",
        3,
        "too slow",
        Duration.parse("PT2M27S")
      )
    )
    for (albumTrack in albumTracks) {
      musicTable.albumTracks.save(albumTrack)
    }
    val playlistInfo = PlaylistInfo(
      "PLAYLIST_1",
      "WFH Music",
      listOf(AlbumTrack.Key("ALBUM_1", 1))
    )
    musicTable.playlistInfo.save(playlistInfo)

    val loadedItems = musicDb.transactionLoad(
      PlaylistInfo.Key("PLAYLIST_1"),
      AlbumTrack.Key("ALBUM_1", 1),
      AlbumTrack.Key("ALBUM_1", 2),
      AlbumTrack.Key("ALBUM_1", 3)
    )
    assertThat(loadedItems.getItems<AlbumTrack>()).containsAll(albumTracks)
    assertThat(loadedItems.getItems<PlaylistInfo>()).contains(playlistInfo)
  }

  @Test
  fun transactionLoadAfterTransactionWrite() {
    val albumTracks = listOf(
      AlbumTrack(
        "ALBUM_1",
        1,
        "dreamin'",
        Duration.parse("PT3M28S")
      ),
      AlbumTrack(
        "ALBUM_1",
        2,
        "what you do to me",
        Duration.parse("PT3M24S")
      ),
      AlbumTrack(
        "ALBUM_1",
        3,
        "too slow",
        Duration.parse("PT2M27S")
      )
    )
    val playlistInfo =
      PlaylistInfo("PLAYLIST_1", "WFH Music", listOf())

    val writeTransaction = TransactionWriteSet.Builder()
      .save(albumTracks[0])
      .save(albumTracks[1])
      .save(albumTracks[2])
      .save(playlistInfo)
      .build()
    musicDb.transactionWrite(writeTransaction)

    // Read items at the same time in a serializable manner.
    val loadedItems = musicDb.transactionLoad(
      PlaylistInfo.Key("PLAYLIST_1"),
      AlbumTrack.Key("ALBUM_1", 1),
      AlbumTrack.Key("ALBUM_1", 2),
      AlbumTrack.Key("ALBUM_1", 3)
    )
    assertThat(loadedItems.getItems<AlbumTrack>()).containsAll(albumTracks)
    assertThat(loadedItems.getItems<PlaylistInfo>()).containsExactly(playlistInfo)
  }

  @Test
  fun conditionalUpdateInTransactionWrite() {
    val playlistInfoV1 =
      PlaylistInfo("PLAYLIST_1", "WFH Music", emptyList())
    musicTable.playlistInfo.save(playlistInfoV1)
    val albumTrack = AlbumTrack(
      "ALBUM_1",
      1,
      "dreamin'",
      Duration.parse("PT3M28S")
    )
    musicTable.albumTracks.save(albumTrack)

    // Add a PlaylistEntry and update PlaylistInfo, in an ACID manner using transactionWrite.
    val playlistInfoV2 = playlistInfoV1.copy(
      playlist_name = "WFH Forever Music",
      playlist_version = playlistInfoV1.playlist_version + 1
    )
    val writeTransaction = TransactionWriteSet.Builder()
      .save(
        playlistInfoV2,
        ifPlaylistVersionIs(playlistInfoV1.playlist_version)
      )
      .delete(AlbumTrack.Key("ALBUM_1", 1))
      .build()
    musicDb.transactionWrite(writeTransaction)

    val loadedItems = musicDb.transactionLoad(
      PlaylistInfo.Key("PLAYLIST_1"),
      AlbumTrack.Key("ALBUM_1", 1)
    )
    assertThat(loadedItems.getItems<PlaylistInfo>()).containsExactly(playlistInfoV2)
    assertThat(loadedItems.getItems<AlbumTrack>()).isEmpty()
  }

  @Test
  fun conditionalUpdateFailureInTransactionWrite() {
    val playlistInfoV1 =
      PlaylistInfo("PLAYLIST_1", "WFH Music", emptyList())
    musicTable.playlistInfo.save(playlistInfoV1)
    val albumTrack = AlbumTrack(
      "ALBUM_1",
      1,
      "dreamin'",
      Duration.parse("PT3M28S")
    )
    musicTable.albumTracks.save(albumTrack)

    // Add a PlaylistEntry and update PlaylistInfo, in an ACID manner using transactionWrite.
    val playlistInfoV2 = playlistInfoV1.copy(
      playlist_version = playlistInfoV1.playlist_version + 1
    )

    val writeTransaction = TransactionWriteSet.Builder()
      .save(
        playlistInfoV2,
        ifPlaylistVersionIs(playlistInfoV1.playlist_version)
      )
      .delete(AlbumTrack.Key("ALBUM_1", 1))
      .build()
    // Introduce a race condition.
    musicTable.playlistInfo.save(playlistInfoV2)

    assertThatExceptionOfType(TransactionCanceledException::class.java)
      .isThrownBy {
        musicDb.transactionWrite(writeTransaction)
      }
      // Confirm the exception message doesn't contain any item data.
      .withMessageContaining(
        "Write transaction failed: [" +
          "Save item (non-key attributes omitted) music_items[partition_key=AttributeValue(S=PLAYLIST_1),sort_key=AttributeValue(S=INFO_)], " +
          "Delete key music_items[partition_key=AttributeValue(S=ALBUM_1),sort_key=AttributeValue(S=TRACK_0000000000000001)]]"
      )
  }

  @Test
  fun conditionCheckInTransactionWrite() {
    val playlistInfoV1 =
      PlaylistInfo("PLAYLIST_1", "WFH Music", emptyList())
    musicTable.playlistInfo.save(playlistInfoV1)
    val albumTrack = AlbumTrack(
      "ALBUM_1",
      1,
      "dreamin'",
      Duration.parse("PT3M28S")
    )
    musicTable.albumTracks.save(albumTrack)

    val playlistInfoV2 = playlistInfoV1.copy(
      playlist_tracks = playlistInfoV1.playlist_tracks + AlbumTrack.Key("ALBUM_1", 1),
      playlist_version = playlistInfoV1.playlist_version + 1
    )
    val writeTransaction = TransactionWriteSet.Builder()
      .save(
        playlistInfoV2,
        ifPlaylistVersionIs(playlistInfoV1.playlist_version)
      )
      // Add a PlaylistEntry only if the AlbumTrack exists.
      .checkCondition(
        AlbumTrack.Key("ALBUM_1", 1),
        trackExists()
      )
      .build()
    musicDb.transactionWrite(writeTransaction)

    val loadedItems = musicDb.transactionLoad(PlaylistInfo.Key("PLAYLIST_1"))
    assertThat(loadedItems.getItems<PlaylistInfo>()).containsExactly(playlistInfoV2)
  }

  @Test
  fun conditionCheckFailureInTransactionWrite() {
    val playlistInfoV1 =
      PlaylistInfo("PLAYLIST_1", "WFH Music", emptyList())
    musicTable.playlistInfo.save(playlistInfoV1)

    val playlistInfoV2 = playlistInfoV1.copy(
      playlist_tracks = playlistInfoV1.playlist_tracks + AlbumTrack.Key("ALBUM_1", 1),
      playlist_version = playlistInfoV1.playlist_version + 1
    )
    val writeTransaction = TransactionWriteSet.Builder()
      .save(
        playlistInfoV2,
        ifPlaylistVersionIs(playlistInfoV1.playlist_version)
      )
      // Add a playlist entry only if the AlbumTrack exists.
      .checkCondition(
        AlbumTrack.Key("ALBUM_1", 1),
        trackExists()
      )
      .build()

    assertThatExceptionOfType(TransactionCanceledException::class.java)
      .isThrownBy {
        musicDb.transactionWrite(writeTransaction)
      }
      // Confirm the exception message doesn't contain any item data.
      .withMessageContaining(
        "Write transaction failed: [" +
          "Save item (non-key attributes omitted) music_items[partition_key=AttributeValue(S=PLAYLIST_1),sort_key=AttributeValue(S=INFO_)], " +
          "Check key music_items[partition_key=AttributeValue(S=ALBUM_1),sort_key=AttributeValue(S=TRACK_0000000000000001)]]"
      )
  }

  @Test
  fun transactionWriteWithAutoGeneratedTimestamp() {
    versionedAttributeDb.transactionWrite(
      TransactionWriteSet.Builder()
        .save(
          VersionedAttribute(
            partition_key = "item_one",
            description = "one of the items",
          )
        )
        .save(
          VersionedAttribute(
            partition_key = "item_two",
            description = "another item",
          )
        )
        .build()
    )

    val loadedItems = versionedAttributeDb.transactionLoad(
      VersionedAttribute.Key("item_one"),
      VersionedAttribute.Key("item_two"),
    )

    loadedItems.getItems<VersionedAttribute>().forEach {
      println("created: $it")
      assertThat(it.created_at_instant).isNotNull()
      assertThat(it.created_at_date).isEqualTo(it.created_at_instant)
      assertThat(it.updated_at_instant).isEqualTo(it.created_at_instant)
      assertThat(it.updated_at_date).isEqualTo(it.created_at_instant)

      // aws extension runs before the tempest one
      assertThat(it.updated_at_dynamo).isEqualTo(it.created_at_instant!!.minus(1.minutes.toJavaDuration()))
    }

    val updateItems = loadedItems.getItems<VersionedAttribute>().mapIndexed { index, it ->
      it.copy(
        description = "${index + 1} updated"
      )
    }

    versionedAttributeDb.transactionWrite(
      TransactionWriteSet.Builder()
        .save(
          updateItems[0]
        )
        .save(
          updateItems[1]
        )
        .build()
    )

    val updatedItems = versionedAttributeDb.transactionLoad(
      VersionedAttribute.Key("item_one"),
      VersionedAttribute.Key("item_two"),
    )

    updatedItems.getItems<VersionedAttribute>().forEach {
      println("updated: $it")
      assertThat(it.created_at_instant).isNotNull()
      assertThat(it.created_at_date).isEqualTo(it.created_at_instant)
      assertThat(it.updated_at_instant).isEqualTo(it.created_at_instant!!.plus(4.minutes.toJavaDuration()))
      assertThat(it.updated_at_date).isEqualTo(it.created_at_instant!!.plus(4.minutes.toJavaDuration()))

      assertThat(it.updated_at_dynamo).isEqualTo(it.updated_at_instant!!.minus(1.minutes.toJavaDuration()))
    }
  }

  private fun ifPlaylistVersionIs(playlist_version: Long): Expression {
    return Expression.builder()
      .expression("playlist_version = :playlist_version")
      .expressionValues(
        mapOf(
          ":playlist_version" to AttributeValue.builder().n("$playlist_version").build()
        )
      )
      .build()
  }

  private fun trackExists(): Expression {
    return Expression.builder()
      .expression("attribute_exists(track_title)")
      .build()
  }
}
