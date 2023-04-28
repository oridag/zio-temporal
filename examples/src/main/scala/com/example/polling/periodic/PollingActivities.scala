package com.example.polling.periodic

import zio.*
import zio.temporal.*
import zio.temporal.activity.*

@activityInterface
trait PollingActivities {
  def doPoll(): String
}

object PeriodicPollingActivityImpl {
  val make: URLayer[TestService with ZActivityOptions[Any], PollingActivities] =
    ZLayer.fromFunction(new PeriodicPollingActivityImpl(_: TestService)(_: ZActivityOptions[Any]))
}

class PeriodicPollingActivityImpl(testService: TestService)(implicit options: ZActivityOptions[Any])
    extends PollingActivities {
  override def doPoll(): String =
    ZActivity.run {
      testService.getServiceResult
    }
}