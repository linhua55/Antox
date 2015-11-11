package chat.tox.antox.av

import chat.tox.antox.tox.ToxSingleton
import chat.tox.antox.utils.{AntoxLog, AudioCapture}
import chat.tox.antox.wrapper.{CallNumber, ContactKey}
import im.tox.tox4j.av._
import im.tox.tox4j.av.enums.{ToxavCallControl, ToxavFriendCallState}
import im.tox.tox4j.exceptions.ToxException
import rx.lang.scala.subjects.BehaviorSubject

class Call(val callNumber: CallNumber, val contactKey: ContactKey) {

  private var friendState: Set[ToxavFriendCallState] = Set()
  val friendStateSubject = BehaviorSubject[Set[ToxavFriendCallState]](friendState)

  private var selfState = SelfCallState.DEFAULT

  //only for outgoing audio
  private val samplingRate = SamplingRate.Rate16k //in Hz
  private val audioLength = AudioLength.Length20 //in microseconds
  private val channels = AudioChannels.Stereo

  val ringing = BehaviorSubject[Boolean](false)
  var incoming = false

  var startTime: Long = 0
  def duration: Long = System.currentTimeMillis() - startTime //in milliseconds

  def active: Boolean = !friendState.contains(ToxavFriendCallState.FINISHED)
  def onHold: Boolean = friendState.isEmpty

  val audioCapture: AudioCapture = new AudioCapture(samplingRate.value, channels.value)
  val audioPlayer = new AudioPlayer(samplingRate.value, channels.value)

  private def frameSize = SampleCount(audioLength, samplingRate)

  friendStateSubject.subscribe(_ => {
    if (active) {
      ringing.onNext(false)
    }
  })

  def logCallEvent(event: String): Unit = AntoxLog.debug(s"Call $callNumber belonging to $contactKey $event")
  
  def startCall(audioBitRate: BitRate, videoBitRate: BitRate): Unit = {
    ToxSingleton.toxAv.call(callNumber.value, audioBitRate, videoBitRate)
    selfState = selfState.copy(audioBitRate = audioBitRate, videoBitRate = videoBitRate)
    incoming = false
    ringing.onNext(true)
  }

  def answerCall(receivingAudio: Boolean, receivingVideo: Boolean): Unit = {
    logCallEvent(s"answered receiving audio:$receivingAudio and video:$receivingVideo")

    ToxSingleton.toxAv.answer(callNumber.value, selfState.audioBitRate, selfState.videoBitRate)
    callStarted(selfState.audioBitRate, selfState.videoBitRate)
    ringing.onNext(false)
  }

  def onIncoming(receivingAudio: Boolean, receivingVideo: Boolean): Unit = {
    logCallEvent(s"incoming receiving audio:$receivingAudio and video:$receivingVideo")

    incoming = true
    ringing.onNext(true)
    selfState = selfState.copy(receivingAudio = receivingAudio, receivingVideo = receivingVideo)
  }

  def updateFriendState(state: Set[ToxavFriendCallState]): Unit = {
    logCallEvent(s"friend call state updated to $state")

    friendState = state
    friendStateSubject.onNext(friendState)
  }

  private def callStarted(audioBitRate: BitRate, videoBitRate: BitRate): Unit = {
    startTime = System.currentTimeMillis()

    logCallEvent(event = s"started with audio bitrate $audioBitRate and video bitrate $videoBitRate")
    
    new Thread(new Runnable {
      override def run(): Unit = {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

        logCallEvent(s"audio encoded thread started")

        audioCapture.start()

        while (active) {
          val start = System.currentTimeMillis()
          if (selfState.sendingAudio) {
            try {
              ToxSingleton.toxAv.audioSendFrame(callNumber.value,
                audioCapture.readAudio(frameSize.value, channels.value),
                frameSize, channels, samplingRate)
            } catch {
              case e: ToxException[_] =>
                end(error = true)
            }
          }

          val timeTaken = System.currentTimeMillis() - start
          if (timeTaken < audioLength.value / 1000)
            Thread.sleep((audioLength.value / 1000) - timeTaken)
        }

        logCallEvent(s"audio encoded thread stopped")
      }
    }, "AudioSendThread").start()

    audioPlayer.start()
  }

  def onAudioFrame(pcm: Array[Short], channels: AudioChannels, samplingRate: SamplingRate): Unit = {
    audioPlayer.bufferAudioFrame(pcm, channels.value, samplingRate.value)
  }

  def muteSelfAudio(): Unit = {
    selfState = selfState.copy(audioMuted = true)
    ToxSingleton.toxAv.setAudioBitRate(callNumber.value, BitRate.Disabled)
    audioCapture.stop()
  }

  def unmuteSelfAudio(): Unit = {
    selfState = selfState.copy(audioMuted = false)
    ToxSingleton.toxAv.setAudioBitRate(callNumber.value, selfState.audioBitRate)
    audioCapture.start()
  }

  def hideSelfVideo(): Unit = {
    selfState = selfState.copy(videoHidden = true)
  }

  def showSelfVideo(): Unit = {
    selfState = selfState.copy(videoHidden = false)
    //TODO
  }

  def muteFriendAudio(): Unit = {
    ToxSingleton.toxAv.callControl(callNumber.value, ToxavCallControl.MUTE_AUDIO)
  }

  def unmuteFriendAudio(): Unit = {
    ToxSingleton.toxAv.callControl(callNumber.value, ToxavCallControl.UNMUTE_AUDIO)
  }

  def hideFriendVideo(): Unit = {
    ToxSingleton.toxAv.callControl(callNumber.value, ToxavCallControl.HIDE_VIDEO)
  }

  def showFriendVideo(): Unit = {
    ToxSingleton.toxAv.callControl(callNumber.value, ToxavCallControl.SHOW_VIDEO)
  }

  def end(error: Boolean = false): Unit = {
    logCallEvent(s"ended error:$error")
    // only send a call control if the call wasn't ended unexpectedly
    if (!error) {
      ToxSingleton.toxAv.callControl(callNumber.value, ToxavCallControl.CANCEL)
    } else {
      updateFriendState(Set(ToxavFriendCallState.FINISHED))
    }

    audioCapture.stop()
    cleanUp()
  }

  private def cleanUp(): Unit = {
    audioPlayer.cleanUp()
    audioCapture.cleanUp()
  }
}
