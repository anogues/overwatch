package com.databricks.labs.overwatch.pipeline

import com.databricks.labs.overwatch.ApiCall
import com.databricks.labs.overwatch.pipeline.TransformFunctions._
import com.databricks.labs.overwatch.utils.{ApiEnv, NoNewDataException, SchemaTools, SparkSessionWrapper, TimeTypes}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.expressions.{Window, WindowSpec}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Column, DataFrame}

import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool

trait SilverTransforms extends SparkSessionWrapper {

  import spark.implicits._

  private val logger: Logger = Logger.getLogger(this.getClass)

  // TODO -- move to Transform Functions
  def structFromJson(df: DataFrame, c: String): Column = {
    require(SchemaTools.getAllColumnNames(df.schema).contains(c), s"The dataframe does not contain col $c")
    require(df.select(SchemaTools.flattenSchema(df): _*).schema.fields.map(_.name).contains(c.replaceAllLiterally(".", "_")), "Column must be a json formatted string")
//    require(df.schema.fields.map(_.name).contains(c), s"The dataframe does not contain col $c")
//    require(df.schema.fields.filter(_.name == c).head.dataType.isInstanceOf[StringType], "Column must be a json formatted string")
    val jsonSchema = spark.read.json(df.select(col(c)).filter(col(c).isNotNull).as[String]).schema
    if (jsonSchema.fields.map(_.name).contains("_corrupt_record")) {
      println(s"WARNING: The json schema for column $c was not parsed correctly, please review.")
    }
    from_json(col(c), jsonSchema).alias(c)
  }

  // TODO -- move to Transform Functions
  def structFromJson(df: DataFrame, cs: String*): Column = {
    val dfFields = df.schema.fields
    cs.foreach(c => {
      require(dfFields.map(_.name).contains(c), s"The dataframe does not contain col $c")
      require(dfFields.filter(_.name == c).head.dataType.isInstanceOf[StringType], "Column must be a json formatted string")
    })
    array(
      cs.map(c => {
        val jsonSchema = spark.read.json(df.select(col(c)).filter(col(c).isNotNull).as[String]).schema
        if (jsonSchema.fields.map(_.name).contains("_corrupt_record")) {
          println(s"WARNING: The json schema for column $c was not parsed correctly, please review.")
        }
        from_json(col(c), jsonSchema).alias(c)
      }): _*
    )
  }

  private def appendPowerProperties: Column = {
    struct(
      element_at('Properties, "sparkappname").alias("AppName"),
      element_at('Properties, "sparkdatabricksapiurl").alias("WorkspaceURL"),
      element_at('Properties, "sparkjobGroupid").alias("JobGroupID"),
      element_at('Properties, "sparkdatabrickscloudProvider").alias("CloudProvider"),
      struct(
        element_at('Properties, "sparkdatabricksclusterSource").alias("ClusterSource"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsautoTerminationMinutes").alias("AutoTerminationMinutes"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterAllTags").alias("ClusterTags"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterAvailability").alias("ClusterAvailability"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterId").alias("ClusterID"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterInstancePoolId").alias("InstancePoolID"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterMaxWorkers").alias("MaxWorkers"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterMinWorkers").alias("MinWorkers"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterName").alias("Name"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterOwnerUserId").alias("OwnerUserID"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterScalingType").alias("ScalingType"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterSpotBidPricePercent").alias("SpotBidPricePercent"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterTargetWorkers").alias("TargetWorkers"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsclusterWorkers").alias("ActualWorkers"),
        element_at('Properties, "sparkdatabricksclusterUsageTagscontainerZoneId").alias("ZoneID"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsdataPlaneRegion").alias("Region"),
        element_at('Properties, "sparkdatabricksclusterUsageTagsdriverNodeType").alias("DriverNodeType"),
        element_at('Properties, "sparkdatabricksworkerNodeTypeId").alias("WorkerNodeType"),
        element_at('Properties, "sparkdatabricksclusterUsageTagssparkVersion").alias("SparkVersion")
      ).alias("ClusterDetails"),
      element_at('Properties, "sparkdatabricksnotebookid").alias("NotebookID"),
      element_at('Properties, "sparkdatabricksnotebookpath").alias("NotebookPath"),
      element_at('Properties, "sparkdatabrickssparkContextId").alias("SparkContextID"),
      element_at('Properties, "sparkdriverhost").alias("DriverHostIP"),
      element_at('Properties, "sparkdrivermaxResultSize").alias("DriverMaxResults"),
      element_at('Properties, "sparkexecutorid").alias("ExecutorID"),
      element_at('Properties, "sparkexecutormemory").alias("ExecutorMemory"),
      element_at('Properties, "sparksqlexecutionid").alias("ExecutionID"),
      element_at('Properties, "sparksqlexecutionparent").alias("ExecutionParent"),
      element_at('Properties, "sparkdatabricksisolationID").alias("SparkDBIsolationID"),
      element_at('Properties, "sparkhadoopfsazureaccountoauth2clientid").alias("AzureOAuth2ClientID"),
      element_at('Properties, "sparkrddscope").alias("RDDScope"),
      element_at('Properties, "sparkdatabricksreplId").alias("SparkDBREPLID"),
      element_at('Properties, "sparkdatabricksjobid").alias("SparkDBJobID"),
      element_at('Properties, "sparkdatabricksjobrunId").alias("SparkDBRunID"),
      element_at('Properties, "sparkdatabricksjobtype").alias("sparkDBJobType"),
      element_at('Properties, "sparksqlshufflepartitions").alias("ShufflePartitions"),
      element_at('Properties, "user").alias("UserEmail"),
      element_at('Properties, "userID").alias("UserID")
    )
  }

  // TODO -- Value Opportunity -- Issue_84

  // TODO -- Issue_81

  //  protected def getJDBCSession(sessionStartDF: DataFrame, sessionEndDF: DataFrame): DataFrame = {
  //    sessionStartDF
  //      .join(sessionEndDF, Seq("SparkContextID", "sessionId"))
  //      .withColumn("ServerSessionRunTime",
  //        TransformFunctions.subtractTime('startTime, 'finishTime))
  //      .drop("startTime", "finishTime")
  //  }
  //
  //  protected def getJDBCOperation(operationStartDF: DataFrame, operationEndDF: DataFrame): DataFrame = {
  //    operationStartDF
  //      .join(operationEndDF, Seq("SparkContextID", "id"))
  //      .withColumn("ServerOperationRunTime",
  //        TransformFunctions.subtractTime('startTime, 'closeTime))
  //      .drop("startTime", "finishTime")
  //  }

  private def simplifyExecutorAdded(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerExecutorAdded")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID, 'ExecutorID, 'executorInfo, 'Timestamp.alias("executorAddedTS"),
        'filenameGroup.alias("startFilenameGroup"))
  }

  private def simplifyExecutorRemoved(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerExecutorRemoved")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID, 'ExecutorID, 'RemovedReason, 'Timestamp.alias("executorRemovedTS"),
        'filenameGroup.alias("endFilenameGroup"))
  }

  protected def executor()(df: DataFrame): DataFrame = {


    val executorAdded = simplifyExecutorAdded(df).alias("executorAdded")
    val executorRemoved = simplifyExecutorRemoved(df).alias("executorRemoved")
    val joinKeys = Seq("organization_id", "fileCreateDate", "clusterId", "SparkContextID", "ExecutorID")

    executorAdded
      .joinWithLag(executorRemoved, joinKeys, "fileCreateDate")
      .withColumn("TaskRunTime",
        TransformFunctions.subtractTime('executorAddedTS, 'executorRemovedTS))
      .drop("executorAddedTS", "executorRemovedTS", "fileCreateDate")
      .withColumn("ExecutorAliveTime", struct(
        $"TaskRunTime.startEpochMS".alias("AddedEpochMS"),
        $"TaskRunTime.startTS".alias("AddedTS"),
        $"TaskRunTime.endEpochMS".alias("RemovedEpochMS"),
        $"TaskRunTime.endTS".alias("RemovedTS"),
        $"TaskRunTime.runTimeMS".alias("uptimeMS"),
        $"TaskRunTime.runTimeS".alias("uptimeS"),
        $"TaskRunTime.runTimeM".alias("uptimeM"),
        $"TaskRunTime.runTimeH".alias("uptimeH")
      ))
      .withColumn("addedTimestamp", $"ExecutorAliveTime.AddedEpochMS")
  }

  protected def enhanceApplication()(df: DataFrame): DataFrame = {
    df.select('organization_id, 'SparkContextID, 'AppID, 'AppName,
      TransformFunctions.toTS('Timestamp).alias("AppStartTime"),
      'Pipeline_SnapTS, 'filenameGroup)
  }

  private val uniqueTimeWindow = Window.partitionBy('organization_id, 'SparkContextID, 'executionId)

  private def simplifyExecutionsStart(df: DataFrame): DataFrame = {
    df.filter('Event === "org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionStart")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID, 'description, 'details, 'executionId.alias("ExecutionID"),
        'time.alias("SqlExecStartTime"),
        'filenameGroup.alias("startFilenameGroup"))
      .withColumn("timeRnk", rank().over(uniqueTimeWindow.orderBy('SqlExecStartTime)))
      .withColumn("timeRn", row_number().over(uniqueTimeWindow.orderBy('SqlExecStartTime)))
      .filter('timeRnk === 1 && 'timeRn === 1)
      .drop("timeRnk", "timeRn")
  }

  private def simplifyExecutionsEnd(df: DataFrame): DataFrame = {
    df.filter('Event === "org.apache.spark.sql.execution.ui.SparkListenerSQLExecutionEnd")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID, 'executionId.alias("ExecutionID"),
        'time.alias("SqlExecEndTime"),
        'filenameGroup.alias("endFilenameGroup"))
      .withColumn("timeRnk", rank().over(uniqueTimeWindow.orderBy('SqlExecEndTime)))
      .withColumn("timeRn", row_number().over(uniqueTimeWindow.orderBy('SqlExecEndTime.desc)))
      .filter('timeRnk === 1 && 'timeRn === 1)
      .drop("timeRnk", "timeRn")
  }

  protected def sqlExecutions()(df: DataFrame): DataFrame = {
    val executionsStart = simplifyExecutionsStart(df).alias("executionsStart")
    val executionsEnd = simplifyExecutionsEnd(df).alias("executionsEnd")
    val joinKeys = Seq("organization_id", "fileCreateDate", "clusterId", "SparkContextID", "ExecutionID")

    //TODO -- review if skew is necessary -- on all DFs
    executionsStart
      .joinWithLag(executionsEnd, joinKeys, "fileCreateDate")
      .withColumn("SqlExecutionRunTime",
        TransformFunctions.subtractTime('SqlExecStartTime, 'SqlExecEndTime))
      .drop("SqlExecStartTime", "SqlExecEndTime", "fileCreateDate")
      .withColumn("startTimestamp", $"SqlExecutionRunTime.startEpochMS")
  }


  protected def simplifyJobStart(sparkEventsBronze: DataFrame): DataFrame = {
    sparkEventsBronze.filter('Event.isin("SparkListenerJobStart"))
      .withColumn("PowerProperties", appendPowerProperties)
      .select(
        'organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID,
        $"PowerProperties.JobGroupID", $"PowerProperties.ExecutionID",
        'JobID, 'StageIDs, 'SubmissionTime, 'PowerProperties,
        'filenameGroup.alias("startFilenameGroup")
      )
  }

  protected def simplifyJobEnd(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerJobEnd")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID, 'JobID, 'JobResult, 'CompletionTime,
        'filenameGroup.alias("endFilenameGroup"))
  }

  protected def sparkJobs()(df: DataFrame): DataFrame = {
    val jobStart = simplifyJobStart(df).alias("jobStart")
    val jobEnd = simplifyJobEnd(df).alias("jobEnd")
    val joinKeys = Seq("organization_id", "fileCreateDate", "clusterId", "SparkContextID", "JobID")

    jobStart
      .joinWithLag(jobEnd, joinKeys, "fileCreateDate")
      .withColumn("JobRunTime", TransformFunctions.subtractTime('SubmissionTime, 'CompletionTime))
      .drop("SubmissionTime", "CompletionTime", "fileCreateDate")
      .withColumn("startTimestamp", $"JobRunTime.startEpochMS")
      .withColumn("startDate", $"JobRunTime.startTS".cast("date"))
  }

  protected def simplifyStageStart(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerStageSubmitted")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID,
        $"StageInfo.StageID", $"StageInfo.SubmissionTime", $"StageInfo.StageAttemptID",
        'StageInfo.alias("StageStartInfo"),
        'filenameGroup.alias("startFilenameGroup"))
  }

  protected def simplifyStageEnd(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerStageCompleted")
      .select('organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID,
        $"StageInfo.StageID", $"StageInfo.StageAttemptID", $"StageInfo.CompletionTime",
        'StageInfo.alias("StageEndInfo"), 'filenameGroup.alias("endFilenameGroup")
      )
  }

  protected def sparkStages()(df: DataFrame): DataFrame = {
    val stageStart = simplifyStageStart(df).alias("stageStart")
    val stageEnd = simplifyStageEnd(df).alias("stageEnd")
    val joinKeys = Seq("organization_id", "fileCreateDate", "clusterId", "SparkContextID", "StageID", "StageAttemptID")

    stageStart
      .joinWithLag(stageEnd, joinKeys, "fileCreateDate")
      .withColumn("StageInfo", struct(
        $"StageEndInfo.Accumulables", $"StageEndInfo.CompletionTime", $"StageStartInfo.Details",
        $"StageStartInfo.FailureReason", $"StageEndInfo.NumberofTasks",
        $"StageStartInfo.ParentIDs", $"StageStartInfo.SubmissionTime"
      )).drop("StageEndInfo", "StageStartInfo")
      .withColumn("StageRunTime", TransformFunctions.subtractTime('SubmissionTime, 'CompletionTime))
      .drop("SubmissionTime", "CompletionTime", "fileCreateDate")
      .withColumn("startTimestamp", $"StageRunTime.startEpochMS")
      .withColumn("startDate", $"StageRunTime.startTS".cast("date"))
  }

  // TODO -- Add in Specualtive Tasks Event == org.apache.spark.scheduler.SparkListenerSpeculativeTaskSubmitted
  protected def simplifyTaskStart(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerTaskStart")
      .select(
        // keys
        'organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID,
        'StageID, 'StageAttemptID, $"TaskInfo.TaskID", $"TaskInfo.ExecutorID",
        $"TaskInfo.Attempt".alias("TaskAttempt"),
        $"TaskInfo.Host", $"TaskInfo.LaunchTime",
        // mesasures
        'TaskInfo.alias("TaskStartInfo"),
        'filenameGroup.alias("startFilenameGroup")
      )
  }

  protected def simplifyTaskEnd(df: DataFrame): DataFrame = {
    df.filter('Event === "SparkListenerTaskEnd")
      .select(
        // keys
        'organization_id, 'fileCreateDate, 'clusterId, 'SparkContextID,
        'StageID, 'StageAttemptID, 'TaskEndReason, $"TaskInfo.TaskID", $"TaskInfo.ExecutorID",
        $"TaskInfo.Attempt".alias("TaskAttempt"),
        $"TaskInfo.Host",
        // measures
        $"TaskInfo.FinishTime",
        'TaskInfo.alias("TaskEndInfo"),
        'TaskMetrics,
        'TaskType,
        'TaskExecutorMetrics,
        'filenameGroup.alias("endFilenameGroup")
      )
  }

  // Orphan tasks are a result of "TaskEndReason.Reason" != "Success"
  // Failed tasks lose association with their chain
  protected def sparkTasks()(df: DataFrame): DataFrame = {
    val taskStart = simplifyTaskStart(df).alias("taskStart")
    val taskEnd = simplifyTaskEnd(df).alias("taskEnd")
    val joinKeys = Seq(
      "organization_id", "fileCreateDate", "clusterId", "SparkContextID",
      "StageID", "StageAttemptID", "TaskID", "TaskAttempt", "ExecutorID", "Host"
    )

    taskStart.joinWithLag(taskEnd, joinKeys, "fileCreateDate")
      .withColumn("TaskInfo", struct(
        $"TaskEndInfo.Accumulables", $"TaskEndInfo.Failed", $"TaskEndInfo.FinishTime",
        $"TaskEndInfo.GettingResultTime",
        $"TaskEndInfo.Host", $"TaskEndInfo.Index", $"TaskEndInfo.Killed", $"TaskStartInfo.LaunchTime",
        $"TaskEndInfo.Locality", $"TaskEndInfo.Speculative"
      )).drop("TaskStartInfo", "TaskEndInfo", "fileCreateDate")
      .withColumn("TaskRunTime", TransformFunctions.subtractTime('LaunchTime, 'FinishTime)).drop("LaunchTime", "FinishTime")
      .withColumn("startTimestamp", $"TaskRunTime.startEpochMS")
      .withColumn("startDate", $"TaskRunTime.startTS".cast("date"))
  }

  // TODO -- Azure Review
  //  Tested in AWS, azure account logic may be slightly different
  protected def accountLogins()(df: DataFrame): DataFrame = {
    val endpointLogins = df.filter(
      'serviceName === "accounts" &&
        lower('actionName).like("%login%") &&
        $"userIdentity.email" =!= "dbadmin")

    val sshLoginRaw = df.filter('serviceName === "ssh" && 'actionName === "login")
      .withColumn("actionName", lit("ssh"))

    val accountLogins = endpointLogins.unionByName(sshLoginRaw)

    val sshDetails = sshLoginRaw.select(
      'timestamp,
      'organization_id,
      'date,
      'requestId,
      lit("ssh").alias("login_type"),
      struct(
        $"requestParams.instanceId".alias("instance_id"),
        $"requestParams.port".alias("login_port"),
        'sessionId.alias("session_id"),
        $"requestParams.publicKey".alias("login_public_key"),
        $"requestParams.containerId".alias("container_id"),
        $"requestParams.userName".alias("container_user_name")
      ).alias("ssh_login_details")
    )

    if (!accountLogins.isEmpty) {
      accountLogins
        .select(
          'timestamp,
          'organization_id,
          'date,
          'date.alias("login_date"),
          'serviceName,
          'actionName.alias("login_type"),
          $"requestParams.user".alias("login_user"),
          //        $"requestParams.userName".alias("ssh_user_name"), TODO -- these are null in Azure - verify on AWS
          //        $"requestParams.user_name".alias("groups_user_name"),
          //        $"requestParams.userID".alias("account_admin_userID"),
          $"userIdentity.email".alias("user_email"),
          'sourceIPAddress, 'userAgent, 'requestId, 'response
        )
        .join(sshDetails, Seq("organization_id", "date", "timestamp", "login_type", "requestId"), "left")
        .drop("date")
    } else
      throw new NoNewDataException("No New Data", Level.INFO, allowModuleProgression = true)
  }

  // TODO -- Azure Review
  //  Tested in AWS, azure account logic may be slightly different
  protected def accountMods()(df: DataFrame): DataFrame = {
    val uidLookup = Window.partitionBy('organization_id, 'user_name).orderBy('timestamp)
      .rowsBetween(Window.currentRow, Window.unboundedFollowing)
    val modifiedAccountsDF = df.filter('serviceName === "accounts" &&
      'actionName.isin("add", "addPrincipalToGroup", "removePrincipalFromGroup", "setAdmin", "updateUser", "delete"))
    if (!modifiedAccountsDF.isEmpty) {
      modifiedAccountsDF
        .select(
          'date, 'timestamp, 'organization_id, 'serviceName, 'actionName,
          $"requestParams.endpoint",
          'requestId,
          $"userIdentity.email".alias("modified_by"),
          $"requestParams.targetUserName".alias("user_name"),
          $"requestParams.targetUserId".alias("user_id"),
          $"requestParams.targetGroupName".alias("group_name"),
          $"requestParams.targetGroupId".alias("group_id"),
          'sourceIPAddress, 'userAgent, 'response
        )
        .withColumn("user_id", when('user_id.isNull, first('user_id, true).over(uidLookup))
          .otherwise('user_id))

    } else
      throw new NoNewDataException("No New Data", Level.INFO, allowModuleProgression = true)
  }

  private val auditBaseCols: Array[Column] = Array(
    'timestamp, 'date, 'organization_id, 'serviceName, 'actionName,
    $"userIdentity.email".alias("userEmail"), 'requestId, 'response)

  private def clusterBase(auditRawDF: DataFrame): DataFrame = {
    val cluster_id_gen_w = Window.partitionBy('organization_id, 'cluster_name).orderBy('timestamp).rowsBetween(Window.currentRow, 1000)
    val cluster_name_gen_w = Window.partitionBy('organization_id, 'cluster_id).orderBy('timestamp).rowsBetween(Window.currentRow, 1000)
    val cluster_id_gen = first('cluster_id, true).over(cluster_id_gen_w)
    val cluster_name_gen = first('cluster_name, true).over(cluster_name_gen_w)

    val clusterSummaryCols = auditBaseCols ++ Array[Column](
      when('actionName === "create", get_json_object($"response.result", "$.cluster_id"))
        .when('actionName =!= "create" && 'cluster_id.isNull, 'clusterId)
        .otherwise('cluster_id).alias("cluster_id"),
      when('cluster_name.isNull, 'clusterName).otherwise('cluster_name).alias("cluster_name"),
      'driver_node_type_id, 'node_type_id, 'num_workers, 'autoscale,
      'clusterWorkers.alias("actual_workers"), 'autotermination_minutes, 'enable_elastic_disk, 'start_cluster,
      'clusterOwnerUserId, 'cluster_log_conf, 'init_scripts, 'custom_tags, 'ssh_public_keys,
      'cluster_source, 'spark_env_vars, 'spark_conf,
      when('ssh_public_keys.isNotNull, true).otherwise(false).alias("has_ssh_keys"),
      'acl_path_prefix, 'instance_pool_id, 'instance_pool_name, 'spark_version, 'cluster_creator, 'idempotency_token,
      'user_id, 'sourceIPAddress, 'single_user_name)

    auditRawDF
      .filter('serviceName === "clusters" && !'actionName.isin("changeClusterAcl"))
      .selectExpr("*", "requestParams.*").drop("requestParams", "Overwatch_RunID")
      .select(clusterSummaryCols: _*)
      .withColumn("cluster_id", cluster_id_gen)
      .withColumn("cluster_name", cluster_name_gen)
  }

  protected def buildClusterSpec(
                                  bronze_cluster_snap: PipelineTable,
                                  pools_snapshot: PipelineTable,
                                  auditRawTable: PipelineTable
                                )(df: DataFrame): DataFrame = {
    val lastClusterSnap = Window.partitionBy('organization_id, 'cluster_id).orderBy('Pipeline_SnapTS.desc)
    val clusterBefore = Window.partitionBy('organization_id, 'cluster_id)
      .orderBy('timestamp).rowsBetween(-1000, Window.currentRow)
    val isSingleNode = get_json_object(regexp_replace('spark_conf, "\\.", "_"),
      "$.spark_databricks_cluster_profile") === lit("singleNode")
    val isServerless = get_json_object(regexp_replace('spark_conf, "\\.", "_"),
      "$.spark_databricks_cluster_profile") === lit("serverless")
    val isSQLAnalytics = get_json_object('custom_tags, "$.SqlEndpointId").isNotNull
    val tableAcls = coalesce(get_json_object(regexp_replace('spark_conf, "\\.", "_"), "$.spark_databricks_acl_dfAclsEnabled").cast("boolean"), lit(false)).alias("table_acls_enabled")
    val passthrough = coalesce(get_json_object(regexp_replace('spark_conf, "\\.", "_"), "$.spark_databricks_passthrough_enabled").cast("boolean"), lit(false)).alias("credential_passthrough_enabled")
    val isolation = coalesce(get_json_object(regexp_replace('spark_conf, "\\.", "_"), "$.spark_databricks_pyspark_enableProcessIsolation").cast("boolean"), lit(false)).alias("isolation_enabled")
    val languagesAllowed = coalesce(split(get_json_object(regexp_replace('spark_conf, "\\.", "_"), "$.spark_databricks_repl_allowedLanguages"), ","), array(lit("All"))).alias("repl_languages_permitted")
    val isSingleUser = 'single_user_name.isNotNull


    val clusterBaseDF = clusterBase(df)

    val poolSnapLookup =  pools_snapshot.asDF
      .withColumn("timestamp", unix_timestamp('Pipeline_SnapTS) * 1000)
      .select('timestamp, 'organization_id, 'instance_pool_id, 'node_type_id.alias("pool_snap_node_type"))

    val instancePoolLookup = auditRawTable.asDF
      .filter('serviceName === "instancePools" && 'actionName === "create")
      .select(
        'timestamp, 'organization_id, get_json_object($"response.result", "$.instance_pool_id").alias("instance_pool_id"),
        $"requestParams.node_type_id".alias("pool_node_type")
      )

    val filledDriverType =
      when('instance_pool_id.isNotNull, 'pool_node_type) // both driver and worker nodes are same when using pool
      .when('cluster_name.like("job-%-run-%"), coalesce('driver_node_type_id, 'node_type_id)) // when jobs clusters workers == driver driver node type is not defined
      .when(isSingleNode, 'node_type_id) // null
      .otherwise(coalesce('driver_node_type_id, first('driver_node_type_id, true).over(clusterBefore), 'node_type_id))
    // Issue_37 -- this will need to be reviewed for mixed pools (i.e. driver of different type)
    val filledDriverType =
      when('instance_pool_id.isNotNull, coalesce('pool_node_type, 'pool_snap_node_type)) // both driver and worker nodes are same when using pool
        .when('cluster_name.like("job-%-run-%"), coalesce('driver_node_type_id, 'node_type_id)) // when jobs clusters workers == driver driver node type is not defined
        .when(isSingleNode, 'node_type_id) // null
        .otherwise(coalesce('driver_node_type_id, first('driver_node_type_id, true).over(clusterBefore), 'node_type_id))

    val filledWorkerType = when(isSingleNode, lit(null).cast("string")) // singleNode clusters don't have worker nodes
      .when('instance_pool_id.isNotNull, coalesce('pool_node_type, 'pool_snap_node_type))
      .otherwise('node_type_id)

    val numWorkers = when(isSingleNode, lit(0).cast("int")).otherwise('num_workers.cast("int"))
    val startCluster = when('start_cluster === "false", lit(false))
      .otherwise(lit(true))
    val enableElasticDisk = when('enable_elastic_disk === "false", lit(false))
      .otherwise(lit(true))

    val clusterSpecBaseCols = Array[Column](
      'serviceName,
      'actionName,
      'organization_id,
      'cluster_id,
      when(isSQLAnalytics, lit("SQLAnalytics")).otherwise('cluster_name).alias("cluster_name"),
      filledDriverType.alias("driver_node_type_id"),
      filledWorkerType.alias("node_type_id"),
      numWorkers.alias("num_workers"),
      'autoscale,
      'autotermination_minutes.cast("int").alias("autotermination_minutes"),
      enableElasticDisk.alias("enable_elastic_disk"),
      isAutomated('cluster_name).alias("is_automated"),
      when(isSingleNode, lit("Single Node"))
        .when(isServerless, lit("Serverless"))
        .when(isSQLAnalytics, lit("SQL Analytics"))
        .otherwise("Standard").alias("cluster_type"),
      'single_user_name,
      startCluster.alias("start_cluster"),
      'cluster_log_conf,
      'init_scripts,
      'custom_tags,
      'cluster_source,
      'spark_env_vars,
      'spark_conf,
      'acl_path_prefix,
      'instance_pool_id,
      'instance_pool_name,
      'spark_version,
      'idempotency_token,
      'timestamp,
      'userEmail)

    val clustersRemoved = clusterBaseDF
      .filter($"response.statusCode" === 200) // only successful delete statements get applied
      .filter('actionName.isin("permanentDelete"))
      .select('organization_id, 'cluster_id, 'userEmail.alias("deleted_by"))

    val creatorLookup = bronze_cluster_snap.asDF
      .withColumn("rnk", rank().over(lastClusterSnap))
      .withColumn("rn", row_number().over(lastClusterSnap))
      .filter('rnk === 1 && 'rn === 1)
      .select('organization_id, 'cluster_id, $"default_tags.Creator".alias("cluster_creator_lookup"))

    clusterBaseDF
      .filter('actionName.isin("create", "edit"))
      .filter('cluster_source =!= "SQL") // not SQL Endpoints
      .toTSDF("timestamp", "organization_id", "instance_pool_id")
      .lookupWhen(
        instancePoolLookup
          .toTSDF("timestamp", "organization_id", "instance_pool_id"), tsPartitionVal = 12
      )
      .lookupWhen(
        poolSnapLookup
          .toTSDF("timestamp", "organization_id", "instance_pool_id"), tsPartitionVal = 12
      ).df
      .select(clusterSpecBaseCols: _*)
      .join(creatorLookup, Seq("organization_id", "cluster_id"), "left")
      .join(clustersRemoved, Seq("organization_id", "cluster_id"), "left")
      .withColumn("security_profile",
        struct(
          tableAcls,
          passthrough,
          isolation,
          languagesAllowed,
          struct(
            isSingleUser.alias("is_single_user"),
            'single_user_name
          ).alias("single_user_profile")
        )
      )
      .withColumn("createdBy",
        when(isAutomated('cluster_name) && 'actionName === "create", lit("JobsService"))
          .when(!isAutomated('cluster_name) && 'actionName === "create", 'userEmail))
      .withColumn("createdBy", when(!isAutomated('cluster_name) && 'createdBy.isNull, last('createdBy, true).over(clusterBefore)).otherwise('createdBy))
      .withColumn("createdBy", when('createdBy.isNull && 'cluster_creator_lookup.isNotNull, 'cluster_creator_lookup).otherwise('createdBy))
      .withColumn("lastEditedBy", when(!isAutomated('cluster_name) && 'actionName === "edit", 'userEmail))
      .withColumn("lastEditedBy", when('lastEditedBy.isNull, last('lastEditedBy, true).over(clusterBefore)).otherwise('lastEditedBy))
      .drop("userEmail", "cluster_creator_lookup", "single_user_name")
  }

  def buildClusterStateDetail(
                             pipelineSnapTime: TimeTypes
                             )(clusterEventsDF: DataFrame): DataFrame = {
    val stateUnboundW = Window.partitionBy('organization_id, 'cluster_id).orderBy('timestamp)
    val stateUntilCurrentW = Window.partitionBy('organization_id, 'cluster_id).rowsBetween(Window.unboundedPreceding, Window.currentRow).orderBy('timestamp)
    val stateUntilPreviousRowW = Window.partitionBy('organization_id, 'cluster_id).rowsBetween(Window.unboundedPreceding, -1L).orderBy('timestamp)
    val uptimeW = Window.partitionBy('organization_id, 'cluster_id, 'reset_partition).orderBy('unixTimeMS_state_start)

    val nonBillableTypes = Array(
      "STARTING", "TERMINATING", "CREATING", "RESTARTING"
    )

    val invalidEventChain = lead('runningSwitch, 1).over(stateUnboundW).isNotNull && lead('runningSwitch, 1).over(stateUnboundW) === lead('previousSwitch, 1).over(stateUnboundW)
    val clusterEventsBaseline = clusterEventsDF
      .selectExpr("*", "details.*")
      .drop("details")
      .withColumnRenamed("type", "state")
      .withColumn(
        "runningSwitch",
        when('state === "TERMINATING", lit(false))
          .when('state.isin("CREATING", "STARTING"), lit(true))
          .otherwise(lit(null).cast("boolean")))
      .withColumn(
        "previousSwitch",
        when('runningSwitch.isNotNull, last('runningSwitch, true).over(stateUntilPreviousRowW))
      )
      .withColumn(
        "invalidEventChainHandler",
        when(invalidEventChain, array(lit(false), lit(true))).otherwise(array(lit(false)))
      )
      .selectExpr("*", "explode(invalidEventChainHandler) as imputedTerminationEvent").drop("invalidEventChainHandler")
      .withColumn("state", when('imputedTerminationEvent, "TERMINATING").otherwise('state))
      .withColumn("timestamp", when('imputedTerminationEvent, lag('timestamp, 1).over(stateUnboundW) + 1L).otherwise('timestamp))
      .withColumn("isRunning", when('imputedTerminationEvent, lit(false)).otherwise(last('runningSwitch, true).over(stateUntilCurrentW)))
      .withColumn(
        "current_num_workers",
        when(!'isRunning || 'isRunning.isNull, lit(null).cast("long"))
          .otherwise(coalesce('current_num_workers, $"cluster_size.num_workers", $"cluster_size.autoscale.min_workers", last(coalesce('current_num_workers, $"cluster_size.num_workers", $"cluster_size.autoscale.min_workers"), true).over(stateUntilCurrentW)))
      )
      .withColumn(
        "target_num_workers",
        when(!'isRunning || 'isRunning.isNull, lit(null).cast("long"))
          .when('state === "CREATING", coalesce($"cluster_size.num_workers", $"cluster_size.autoscale.min_workers"))
          .otherwise(coalesce('target_num_workers, 'current_num_workers))
      )
      .select(
        'organization_id, 'cluster_id, 'isRunning,
        'timestamp, 'state, 'current_num_workers, 'target_num_workers
      )

    clusterEventsBaseline
      .withColumn("counter_reset",
        when(
          lag('state, 1).over(stateUnboundW).isin("TERMINATING", "RESTARTING", "EDITED") ||
            !'isRunning, lit(1)
        ).otherwise(lit(0))
      )
      .withColumn("reset_partition", sum('counter_reset).over(stateUnboundW))
      .withColumn("target_num_workers", last('target_num_workers, true).over(stateUnboundW))
      .withColumn("current_num_workers", last('current_num_workers, true).over(stateUnboundW))
      .withColumn("unixTimeMS_state_start", 'timestamp)
      .withColumn("unixTimeMS_state_end", coalesce( // if state end open, use pipelineSnapTime, will be merged when state end is received
        lead('timestamp, 1).over(stateUnboundW) - lit(1), // subtract 1 millis
        lit(pipelineSnapTime.asUnixTimeMilli)
      ))
      .withColumn("timestamp_state_start", from_unixtime('unixTimeMS_state_start.cast("double") / lit(1000)).cast("timestamp"))
      .withColumn("timestamp_state_end", from_unixtime('unixTimeMS_state_end.cast("double") / lit(1000)).cast("timestamp")) // subtract 1.0 millis
      .withColumn("state_start_date", 'timestamp_state_start.cast("date"))
      .withColumn("uptime_in_state_S", ('unixTimeMS_state_end - 'unixTimeMS_state_start) / lit(1000))
      .withColumn("uptime_since_restart_S",
        coalesce(
          when('counter_reset === 1, lit(0))
            .otherwise(sum('uptime_in_state_S).over(uptimeW)),
          lit(0)
        )
      )
      .withColumn("cloud_billable", 'isRunning)
      .withColumn("databricks_billable", 'isRunning && !'state.isin(nonBillableTypes: _*))
      .withColumn("uptime_in_state_H", 'uptime_in_state_S / lit(3600))
      .withColumn("state_dates", sequence('timestamp_state_start.cast("date"), 'timestamp_state_end.cast("date")))
      .withColumn("days_in_state", size('state_dates))

  }

  /**
   * TODO -- move to transform funcs and pass in expressions as Seq
   *  Generalizing so it can also be used for other DFs
   * @param colName
   * @param w
   * @param withSnapLookup
   * @param path
   * @return
   */
  private def fillForward(colName: String, w: WindowSpec, withSnapLookup: Boolean = true, path: Option[String] = None): Column = {
    val settingsPath = s"'$$.${path.getOrElse(colName)}'"
    val firstNonNullVal: Seq[Column] = Seq(
      expr(s"get_json_object(new_settings, $settingsPath)"),
      col(colName),
      last(expr(s"get_json_object(lookup_settings, $settingsPath)"), true).over(w),
      last(col(colName), true).over(w)
    )

    val firstNonNullSnapLookupVal: Seq[Column] = Seq(get_json_object('lookup_settings, settingsPath))
    val orderedNonNullLookups = if (withSnapLookup) firstNonNullVal ++ firstNonNullSnapLookupVal else firstNonNullVal

    coalesce(orderedNonNullLookups: _*)
  }

  private def getJobsBase(df: DataFrame): DataFrame = {
    df.filter(col("serviceName") === "jobs")
      .selectExpr("*", "requestParams.*").drop("requestParams")
  }

  protected def dbJobsStatusSummary(
                                   jobsSnapshotDF: DataFrame,
                                   cloudProvider: String
                                   )(df: DataFrame): DataFrame = {

    val jobsBase = getJobsBase(df)

    // Simplified DF for snapshot lookupWhen
    val jobSnapLookup = jobsSnapshotDF
      .withColumnRenamed("created_time", "snap_lookup_created_time")
      .withColumnRenamed("creator_user_name", "snap_lookup_created_by")
      .withColumnRenamed("job_id", "jobId")
      .withColumn("lookup_settings", to_json('settings))
      .withColumn("timestamp", unix_timestamp('Pipeline_SnapTS))
      .drop("Overwatch_RunID", "settings")

    val jobs_statusCols: Array[Column] = Array(
      'organization_id, 'serviceName, 'actionName, 'timestamp,
      when('actionName === "create", get_json_object($"response.result", "$.job_id").cast("long"))
        .when('actionName === "changeJobAcl", 'resourceId.cast("long"))
        .otherwise('job_id).cast("long").alias("jobId"),
      when('actionName === "create", 'name)
        .when('actionName.isin("update", "reset"), get_json_object('new_settings, "$.name"))
        .otherwise(lit(null).cast("string")).alias("jobName"),
      'job_type,
      'timeout_seconds.cast("long").alias("timeout_seconds"),
      'schedule,
      get_json_object('notebook_task, "$.notebook_path").alias("notebook_path"),
      'new_settings, 'existing_cluster_id, 'new_cluster, 'aclPermissionSet, 'grants, 'targetUserId,
      'sessionId, 'requestId, 'userAgent, 'userIdentity, 'response, 'sourceIPAddress, 'version
    )

    val lastJobStatus = Window.partitionBy('organization_id, 'jobId).orderBy('timestamp).rowsBetween(Window.unboundedPreceding, Window.currentRow)

    val jobStatusBase = jobsBase
      .filter('actionName.isin("create", "delete", "reset", "update", "resetJobAcl", "changeJobAcl"))
      .select(jobs_statusCols: _*)
      .toTSDF("timestamp", "organization_id", "jobId")
      .lookupWhen(
        jobSnapLookup.toTSDF("timestamp", "organization_id", "jobId")
      ).df
      .withColumn("existing_cluster_id", coalesce('existing_cluster_id, get_json_object('new_settings, "$.existing_cluster_id")))
      .withColumn("new_cluster", coalesce('new_cluster, get_json_object('new_settings, "$.new_cluster")))
      // Following section builds out a "clusterSpec" as it is defined at the timestamp. existing_cluster_id
      // and new_cluster should never both be populated as a job must be one or the other at a timestamp
      .withColumn(
        "x",
        struct(
          when('existing_cluster_id.isNotNull, struct('timestamp, 'existing_cluster_id)).otherwise(lit(null)).alias("last_existing"),
          when('new_cluster.isNotNull, struct('timestamp, 'new_cluster)).otherwise(lit(null)).alias("last_new")
        )
      )
      .withColumn(
        "x2",
        struct(
          last($"x.last_existing", true).over(lastJobStatus).alias("last_existing"),
          last($"x.last_new", true).over(lastJobStatus).alias("last_new"),
        )
      )
      .withColumn(
        "cluster_spec",
        struct(
          when($"x2.last_existing.timestamp" > coalesce($"x2.last_new.timestamp", lit(0)), $"x2.last_existing.existing_cluster_id").otherwise(lit(null)).alias("existing_cluster_id"),
          when($"x2.last_new.timestamp" > coalesce($"x2.last_existing.timestamp", lit(0)), $"x2.last_new.new_cluster").otherwise(lit(null)).alias("new_cluster")
        )
      )
      .withColumn(
        "cluster_spec",
        struct(
          when($"cluster_spec.existing_cluster_id".isNull && $"cluster_spec.new_cluster".isNull, get_json_object('lookup_settings, "$.existing_cluster_id")).otherwise($"cluster_spec.existing_cluster_id").alias("existing_cluster_id"),
          when($"cluster_spec.existing_cluster_id".isNull && $"cluster_spec.new_cluster".isNull, get_json_object('lookup_settings, "$.new_cluster")).otherwise($"cluster_spec.new_cluster").alias("new_cluster")
        )
      ).drop("existing_cluster_id", "new_cluster", "x", "x2") // drop temp columns and old version of clusterSpec components
      .withColumn("job_type", when('job_type.isNull, last('job_type, true).over(lastJobStatus)).otherwise('job_type))
      .withColumn("schedule", fillForward("schedule", lastJobStatus))
      .withColumn("timeout_seconds", fillForward("timeout_seconds", lastJobStatus))
      .withColumn("notebook_path", fillForward("notebook_path", lastJobStatus, path = Some("notebook_task.notebook_path")))
      .withColumn("jobName", fillForward("jobName", lastJobStatus, path = Some("name")))
      .withColumn("created_by", when('actionName === "create", $"userIdentity.email"))
      .withColumn("created_by", coalesce(fillForward("created_by", lastJobStatus), 'snap_lookup_created_by))
      .withColumn("created_ts", when('actionName === "create", 'timestamp))
      .withColumn("created_ts", coalesce(fillForward("created_ts", lastJobStatus), 'snap_lookup_created_time))
      .withColumn("deleted_by", when('actionName === "delete", $"userIdentity.email"))
      .withColumn("deleted_ts", when('actionName === "delete", 'timestamp))
      .withColumn("last_edited_by", when('actionName.isin("update", "reset"), $"userIdentity.email"))
      .withColumn("last_edited_by", last('last_edited_by, true).over(lastJobStatus))
      .withColumn("last_edited_ts", when('actionName.isin("update", "reset"), 'timestamp))
      .withColumn("last_edited_ts", last('last_edited_ts, true).over(lastJobStatus))
      .drop("userIdentity", "snap_lookup_created_time", "snap_lookup_created_by", "lookup_settings")

    // Get as much as possible from snapshot by timestamp for long-standing jobs that haven't been edited that would
    // otherwise be missing from the audit logs and thus missing from jobStatus
    val missingJobIds = jobsSnapshotDF.select('organization_id, 'job_id).distinct
      .join(jobStatusBase.select('organization_id, 'jobId.alias("job_id")).distinct, Seq("organization_id", "job_id"), "anti")

    val jSnapMissingJobs = jobsSnapshotDF
      .join(missingJobIds, Seq("organization_id", "job_id"))
      .withColumn("timestamp", unix_timestamp('Pipeline_SnapTS) * 1000)
      .select(
        'organization_id, 'timestamp, 'job_id.alias("jobId"), lit("jobs").alias("serviceName"), lit("lookup").alias("actionName"),
        $"settings.name".alias("jobName"), $"settings.timeout_seconds", to_json($"settings.schedule").alias("schedule"),
        $"settings.notebook_task.notebook_path",
        when($"settings.existing_cluster_id".isNotNull, struct($"settings.existing_cluster_id", lit(null).alias("new_cluster")))
          .otherwise(struct(lit(null).alias("existing_cluster_id"), to_json($"settings.new_cluster").alias("new_cluster"))).alias("cluster_spec"),
        'creator_user_name.alias("created_by"), 'created_time.alias("created_ts")
      )

    val jobStatusBaseFilled = unionWithMissingAsNull(jobStatusBase, jSnapMissingJobs)

    val changeInventory = Map(
      "cluster_spec.new_cluster" -> structFromJson(jobStatusBaseFilled, "cluster_spec.new_cluster"),
      "new_settings" -> structFromJson(jobStatusBaseFilled, "new_settings"),
      "schedule" -> structFromJson(jobStatusBaseFilled, "schedule")
    )

    // create structs from json strings and cleanse schema
    val jobStatusEnhanced = SchemaTools.scrubSchema(
      jobStatusBaseFilled
        .select(SchemaTools.modifyStruct(jobStatusBaseFilled.schema, changeInventory): _*)
    )

    // convert structs to maps where the structs' keys are allowed to be typed by the user to avoid
    // bad duplicate keys
    val structsCleaner = Map(
      "new_settings.new_cluster.custom_tags" -> SchemaTools.structToMap(jobStatusEnhanced, "new_settings.new_cluster.custom_tags"),
      "new_settings.new_cluster.spark_conf" -> SchemaTools.structToMap(jobStatusEnhanced, "new_settings.new_cluster.spark_conf"),
      "new_settings.new_cluster.spark_env_vars" -> SchemaTools.structToMap(jobStatusEnhanced, "new_settings.new_cluster.spark_env_vars"),
      s"new_settings.new_cluster.${cloudProvider}_attributes" -> SchemaTools.structToMap(jobStatusEnhanced, s"new_settings.new_cluster.${cloudProvider}_attributes"),
      "new_settings.notebook_task.base_parameters" -> SchemaTools.structToMap(jobStatusEnhanced, "new_settings.notebook_task.base_parameters"),
      "cluster_spec.new_cluster.custom_tags" -> SchemaTools.structToMap(jobStatusEnhanced, "cluster_spec.new_cluster.custom_tags"),
      "cluster_spec.new_cluster.spark_conf" -> SchemaTools.structToMap(jobStatusEnhanced, "cluster_spec.new_cluster.spark_conf"),
      "cluster_spec.new_cluster.spark_env_vars" -> SchemaTools.structToMap(jobStatusEnhanced, "cluster_spec.new_cluster.spark_env_vars"),
      s"cluster_spec.new_cluster.${cloudProvider}_attributes" -> SchemaTools.structToMap(jobStatusEnhanced, s"cluster_spec.new_cluster.${cloudProvider}_attributes")
    )

    jobStatusEnhanced
      .select(SchemaTools.modifyStruct(jobStatusEnhanced.schema, structsCleaner): _*)
  }

  /**
   * Depending on the actionName, the runID will be found in one of the following columns, "run_id",
   * "runId", or in the "response.result.run_id" fields.
   *
   * The lineage of a run is as follows:
   * runNow or submitRun: RPC service, happens at RPC request received, this RPC request does not happen when a
   * job is started via databricks cron, in these cases only a runStart is emitted. A manual runstart via the UI
   * emits a runNow
   * runStart: JobRunner service, happens when a run starts on a cluster
   * runSucceeded or runFailed, JobRunner service, happens when job ends
   *
   * To build the logs, the incremental audit logs are reviewed for jobs COMPLETED and CANCELLED in the time scope
   * of this overwatch run. If completed during this run then the entire audit log jobs service is searched for
   * the start of that run
   *
   * @param completeAuditTable
   * @param clusterSpec
   * @param jobsStatus
   * @param jobsSnapshot
   * @param etlStartTime Actual start timestamp of the Overwatch run
   * @param df
   * @return
   */
  protected def dbJobRunsSummary(completeAuditTable: DataFrame,
                                 clusterSpec: PipelineTable,
                                 clusterSnapshot: PipelineTable,
                                 jobsStatus: PipelineTable,
                                 jobsSnapshot: PipelineTable,
                                 apiEnv: ApiEnv,
                                 etlStartTime: TimeTypes)(df: DataFrame): DataFrame = {

    val repartitionCount = spark.conf.get("spark.sql.shuffle.partitions").toInt * 2

    val rawAuditExclusions = StructType(Seq(
      StructField("requestParams", StructType(Seq(
        StructField("organization_id", NullType, nullable = true),
        StructField("orgId", NullType, nullable = true)
      )), nullable = true)
    ))

    val jobsAuditComplete = completeAuditTable
      .verifyMinimumSchema(rawAuditExclusions) // remove interference from source
      .filter('serviceName === "jobs")
      .selectExpr("*", "requestParams.*").drop("requestParams")
      .repartition(repartitionCount)
      .cache()
    jobsAuditComplete.count()

    val jobsAuditIncremental = getJobsBase(df)

    /**
     * JOBS-1709 -- JAWS team should fix multiple event emissions. If they do, this can be removed for a perf gain
     * but for now, this is required or multiple records will be created in the fact due to the multiple events.
     * Confirmed that the first event emitted is the correct event as per ES-65402
     */
    val firstRunSubmitW = Window.partitionBy('organization_id, 'runId).orderBy('timestamp)
    val firstCompletionW = Window.partitionBy('organization_id, 'runId).orderBy('timestamp)
    val firstRunStartW = Window.partitionBy('organization_id, 'runId).orderBy('timestamp)
    val firstCancellationW = Window.partitionBy('organization_id, 'run_id).orderBy('timestamp)

    // Completes must be >= etlStartTime as it is the driver endpoint
    // All joiners to Completes may be from the past up to N days as defined in the incremental df
    // Identify all completed jobs in scope for this overwatch run
    val allCompletes = jobsAuditIncremental
      .filter('date >= etlStartTime.asColumnTS.cast("date") && 'timestamp >= lit(etlStartTime.asUnixTimeMilli))
      .filter('actionName.isin("runSucceeded", "runFailed"))
      .withColumn("rnk", rank().over(firstCompletionW))
      .withColumn("rn", row_number().over(firstCompletionW))
      .filter('rnk === 1 && 'rn === 1)
      .select(
        'serviceName, 'actionName,
        'organization_id,
        'date,
        'runId,
        'jobId,
        'idInJob, 'clusterId, 'jobClusterType, 'jobTaskType, 'jobTerminalState,
        'jobTriggerType,
        'requestId.alias("completionRequestID"),
        'response.alias("completionResponse"),
        'timestamp.alias("completionTime")
      )

    // CancelRequests are still lookups from the driver "complete" as a cancel request is a request and still
    // results in a runFailed after the cancellation
    // Identify all cancelled jobs in scope for this overwatch run
    val allCancellations = jobsAuditIncremental
      .filter('date >= etlStartTime.asColumnTS.cast("date") && 'timestamp >= lit(etlStartTime.asUnixTimeMilli))
      .filter('actionName.isin("cancel"))
      .withColumn("rnk", rank().over(firstCancellationW))
      .withColumn("rn", row_number().over(firstCancellationW))
      .filter('rnk === 1 && 'rn === 1)
      .select(
        'organization_id, 'date,
        'run_id.cast("long").alias("runId"),
        'requestId.alias("cancellationRequestId"),
        'response.alias("cancellationResponse"),
        'sessionId.alias("cancellationSessionId"),
        'sourceIPAddress.alias("cancellationSourceIP"),
        'timestamp.alias("cancellationTime"),
        'userAgent.alias("cancelledUserAgent"),
        'userIdentity.alias("cancelledBy")
      ).filter('runId.isNotNull)

    // DF for jobs launched with actionName == "runNow"
    val runNowStart = jobsAuditIncremental
      .filter('actionName.isin("runNow"))
      .select(
        'organization_id, 'date,
        get_json_object($"response.result", "$.run_id").cast("long").alias("runId"),
        lit(null).cast("string").alias("run_name"),
        'timestamp.alias("submissionTime"),
        lit(null).cast("string").alias("new_cluster"),
        lit(null).cast("string").alias("existing_cluster_id"),
        'notebook_params, 'workflow_context,
        struct(
          lit(null).cast("string").alias("notebook_task"),
          lit(null).cast("string").alias("spark_python_task"),
          lit(null).cast("string").alias("spark_jar_task"),
          lit(null).cast("string").alias("shell_command_task")
        ).alias("taskDetail"),
        lit(null).cast("string").alias("libraries"),
        lit(null).cast("long").alias("timeout_seconds"),
        'sourceIPAddress.alias("submitSourceIP"),
        'sessionId.alias("submitSessionId"),
        'requestId.alias("submitRequestID"),
        'response.alias("submitResponse"),
        'userAgent.alias("submitUserAgent"),
        'userIdentity.alias("submittedBy")
      ).filter('runId.isNotNull)

    // DF for jobs launched with actionName == "submitRun"
    val runSubmitStart = jobsAuditIncremental
      .filter('actionName.isin("submitRun"))
      .withColumn("runId", get_json_object($"response.result", "$.run_id").cast("long"))
      .withColumn("rnk", rank().over(firstRunSubmitW))
      .withColumn("rn", row_number().over(firstRunSubmitW))
      .filter('rnk === 1 && 'rn === 1)
      .select(
        'organization_id, 'date,
        'runId,
        'run_name,
        'timestamp.alias("submissionTime"),
        'new_cluster, 'existing_cluster_id,
        'notebook_params, 'workflow_context,
        struct(
          'notebook_task,
          'spark_python_task,
          'spark_jar_task,
          'shell_command_task
        ).alias("taskDetail"),
        'libraries,
        'timeout_seconds.cast("long").alias("timeout_seconds"),
        'sourceIPAddress.alias("submitSourceIP"),
        'sessionId.alias("submitSessionId"),
        'requestId.alias("submitRequestID"),
        'response.alias("submitResponse"),
        'userAgent.alias("submitUserAgent"),
        'userIdentity.alias("submittedBy")
      )
      .filter('runId.isNotNull)

    // DF to pull unify differing schemas from runNow and submitRun and pull all job launches into one DF
    val allSubmissions = runNowStart
      .unionByName(runSubmitStart)

    // Find the corresponding runStart action for the completed jobs
    val runStarts = jobsAuditIncremental
      .filter('actionName.isin("runStart"))
      .withColumn("rnk", rank().over(firstRunStartW))
      .withColumn("rn", row_number().over(firstRunStartW))
      .filter('rnk === 1 && 'rn === 1)
      .select(
        'organization_id, 'date,
        'runId,
        'clusterId.alias("startClusterId"),
        'timestamp.alias("startTime"),
        'requestId.alias("startRequestID")
      )

    // Lookup to populate the clusterID/clusterName where missing from jobs
    lazy val clusterSpecLookup = clusterSpec.asDF
      .select('organization_id, 'timestamp, 'cluster_name, 'cluster_id.alias("clusterId"))
      .filter('clusterId.isNotNull && 'cluster_name.isNotNull)

    // Lookup to populate the clusterID/clusterName where missing from jobs
    lazy val clusterSnapLookup = clusterSnapshot.asDF
      .withColumn("timestamp", unix_timestamp('Pipeline_SnapTS) * lit(1000))
      .select('organization_id, 'timestamp, 'cluster_name, 'cluster_id.alias("clusterId"))
      .filter('clusterId.isNotNull && 'cluster_name.isNotNull)

    // Lookup to populate the existing_cluster_id where missing from jobs -- it can be derived from name
    lazy val existingClusterLookup = jobsStatus.asDF
//      .select('organization_id, 'timestamp, 'jobId, 'existing_cluster_id.alias("clusterId"))
      .select('organization_id, 'timestamp, 'jobId, $"cluster_spec.existing_cluster_id".alias("clusterId"))
      .filter('clusterId.isNotNull)

//    // Lookup to populate the existing_cluster_id where missing from jobs -- it can be derived from name
//    lazy val existingClusterLookup2 = if (SchemaTools.nestedColExists(jobsSnapshot.asDF, "settings.existing_cluster_id")) {
//      jobsSnapshot.asDF
//        .select('organization_id, 'job_id.alias("jobId"), $"settings.existing_cluster_id".alias("clusterId"),
//          'created_time.alias("timestamp"))
//    } else {
//      jobsSnapshot.asDF
//        .select(
//          'organization_id,
//          'job_id.alias("jobId"),
//          'created_time.alias("timestamp"),
//          lit(null).cast("string").alias("clusterId")
//        )
//    }

    // Ensure the lookups have data -- this will be improved when the module unification occurs during the next
    // scheduled refactor
    val clusterLookups: scala.collection.mutable.Map[String, DataFrame] = scala.collection.mutable.Map()
    val jobStatusClusterLookups: scala.collection.mutable.Map[String, DataFrame] = scala.collection.mutable.Map()

    if (clusterSpec.exists) clusterLookups.put("clusterSpecLookup", clusterSpecLookup)
    if (clusterSnapshot.exists) clusterLookups.put("clusterSnapLookup", clusterSnapLookup)
    if (jobsStatus.exists) jobStatusClusterLookups.put("jobStatusLookup", existingClusterLookup)
//    if (jobsSnapshot.exists) jobStatusClusterLookups.put("jobSnapshotLookup", existingClusterLookup2)

    val jobRunsBase = allCompletes
      .join(allSubmissions, Seq("runId", "organization_id"), "left")
      .join(allCancellations, Seq("runId", "organization_id"), "left")
      .join(runStarts, Seq("runId", "organization_id"), "left")
      .withColumn("jobTerminalState", when('cancellationRequestId.isNotNull, "Cancelled").otherwise('jobTerminalState)) //.columns.sorted
      .withColumn("jobRunTime", TransformFunctions.subtractTime(array_min(array('startTime, 'submissionTime)), array_max(array('completionTime, 'cancellationTime))))
      .withColumn("cluster_name", when('jobClusterType === "new", concat(lit("job-"), 'jobId, lit("-run-"), 'idInJob)).otherwise(lit(null)))
      .select(
        'runId.cast("long"), 'jobId.cast("long"), 'idInJob, 'jobRunTime, 'run_name,
        'jobClusterType, 'jobTaskType, 'jobTerminalState,
        'jobTriggerType, 'new_cluster,
        when('actionName.isin("runStart", "runFailed", "runSucceeded"), coalesce('clusterId, 'startClusterId)) // notebookRuns
          .otherwise('existing_cluster_id).alias("clusterId"), //
        'cluster_name,
        'organization_id,
        'notebook_params, 'libraries,
        'workflow_context, 'taskDetail,
        struct(
          'startTime,
          'submissionTime,
          'cancellationTime,
          'completionTime,
          'timeout_seconds
        ).alias("timeDetails"),
        struct(
          struct(
            'submitRequestId,
            'submitResponse,
            'submitSessionId,
            'submitSourceIP,
            'submitUserAgent,
            'submittedBy
          ).alias("submissionRequest"),
          struct(
            'cancellationRequestId,
            'cancellationResponse,
            'cancellationSessionId,
            'cancellationSourceIP,
            'cancelledUserAgent,
            'cancelledBy
          ).alias("cancellationRequest"),
          struct(
            'completionRequestId,
            'completionResponse
          ).alias("completionRequest"),
          struct(
            'startRequestId
          ).alias("startRequest")
        ).alias("requestDetails")
      )
      .withColumn("timestamp", $"jobRunTime.endEpochMS")

    /**
     * ES-74247 requires hit to API for notebook jobs running on existing clusters. PR submitted and will be
     * rolled into 3.42 but until then, this is the hack around.
     */

    /**
     * UPDATE -- as of 0.4.13, enabled fix from ES-74247 and resolved Issue_138 thus this api call is only for
     * legacy audit logs prior to approximately April 01, 2021. If historically loading audit logs, this api call
     * could still be necessary; otherwise, it should never be hit.
     */

    logger.log(Level.INFO, s"HISTORICAL TRICKLE LOAD: notebook job run on existing clusters have been " +
      s"identified prior to the April 01, 2021 resolution, the historical data will be trickle loaded. Please be " +
      s"patient as this is a one-time trickle load to back-load old data.")
    val taskSupport = new ForkJoinTaskSupport(new ForkJoinPool(2))
    val jrWFilledClusterIDs = jobRunsBase
      .filter('jobClusterType === "existing")
      .filter('jobTaskType === "notebook" && 'clusterId.isNull)
      .filter(datediff(
        from_unixtime(lit(System.currentTimeMillis().toDouble / 1000)).cast("timestamp").cast("date"),
        $"jobRunTime.startTS".cast("date")) <= 67
      )
      .select('runId)
      .distinct()
      .as[Long]
      .collect()
      .par

    // If current batch does not include any runs with existing clusters, omit this lookup altogether.
    val jrBaseExisting = if (jrWFilledClusterIDs.nonEmpty) {
      jrWFilledClusterIDs.tasksupport = taskSupport

      logger.log(Level.INFO, s"RUN_IDs staged for api grabber: ${jrWFilledClusterIDs.mkString(", ")}")

      val runIdStrings = jrWFilledClusterIDs
        .flatMap(runId => {
          try {
            val q = Map(
              "run_id" -> runId
            )
            val runIDString = ApiCall("jobs/runs/get", apiEnv, Some(q), paginate = false)
              .executeGet().asStrings
            logger.log(Level.INFO, s"API CALL SUCCESS: RUN_ID == $runId")
            runIDString
          } catch {
            case e: Throwable =>
              logger.log(Level.ERROR, s"API CALL FAILED: RUN_ID == $runId --> ${e.getMessage}")
              Array[String]()
          }
        })
        .toArray

      val notebookJobsWithClusterIDs = if(runIdStrings.nonEmpty){
        spark.read.json(Seq(runIdStrings: _*).toDS())
          .select('run_id.alias("runId"), $"cluster_spec.existing_cluster_id".alias("cluster_id_apiLookup"))
      } else { // defend against API runId calls with no result missing
        val missingRunIDMsg = s"A cluster ID could not be determined for the following runs. ${jrWFilledClusterIDs.toArray.mkString(", ")}"
        logger.log(Level.WARN, missingRunIDMsg)
        spark.emptyDataFrame
          .withColumn("runId", lit(null).cast("long"))
          .withColumn("cluster_id_apiLookup", lit(null).cast("string"))
      }

      // If batch has runs on existing clusters older than the API retention the previous DF will be empty. Only join
      // if the api lookup successfully obtained the data
      if (!notebookJobsWithClusterIDs.isEmpty) {
        jobRunsBase
          .filter('jobClusterType === "existing")
          .join(notebookJobsWithClusterIDs, Seq("runId"), "left")
          .withColumn("clusterId", when('jobTaskType === "notebook" && 'clusterId.isNull, 'cluster_id_apiLookup).otherwise('clusterId))
          .drop("cluster_id_apiLookup")
      } else jobRunsBase
    } else {
      jobRunsBase
        .filter('jobClusterType === "existing")
    }

    val automatedJobRunsBase = jobRunsBase
      .filter('jobClusterType === "new")

    // LOOKUP - ClusterID By JobID -- EXISTING CLUSTER JOB RUNS
    // JobRuns with interactive clusters
    // If the lookups are present (likely will be unless is first run or modules are turned off somehow)
    // use the lookups to populate the null clusterIds and cluster_names
    val interactiveRunsWID = if (jobStatusClusterLookups.nonEmpty) {
      var jrBaseExistingBuilder = jrBaseExisting

      if (jobStatusClusterLookups.contains("jobStatusLookup")) {
        jrBaseExistingBuilder = jrBaseExistingBuilder
          .toTSDF("timestamp", "organization_id", "jobId")
          .lookupWhen(
            jobStatusClusterLookups("jobStatusLookup")
              .toTSDF("timestamp", "organization_id", "jobId")
          ).df
      }

//      if (jobStatusClusterLookups.contains("jobSnapshotLookup")) {
//        jrBaseExistingBuilder = jrBaseExistingBuilder
//          .toTSDF("timestamp", "organization_id", "jobId")
//          .lookupWhen(
//            jobStatusClusterLookups("jobSnapshotLookup")
//              .toTSDF("timestamp", "organization_id", "jobId")
//          ).df
//      }
      jrBaseExistingBuilder

    } else jrBaseExisting

    // Re-Combine Interactive and Automated Job Run Clusters
    val jobRunsBaseWIDs = interactiveRunsWID.unionByName(automatedJobRunsBase)

    // Get JobName for All Jobs
    val previousJobNameW = Window.partitionBy('organization_id, 'jobId).orderBy('timestamp).rowsBetween(Window.unboundedPreceding, Window.currentRow)

    val newJobsNameLookup = jobsAuditComplete
      .select(
        'organization_id, 'timestamp,
        get_json_object($"response.result", "$.job_id").cast("long").alias("jobId"),
        'name.alias("jobName")
      )

    val editJobsNameLookup = jobsAuditComplete
      .filter('actionName.isin("reset", "update"))
      .select('organization_id, 'timestamp, 'job_id.alias("jobId").cast("long"), get_json_object('new_settings, "$.name").alias("jobName"))
      .withColumn("jobName", when('jobName.isNull, last('jobName, true).over(previousJobNameW)).otherwise('jobName))

    val jobNameLookupFromAudit = newJobsNameLookup.unionByName(editJobsNameLookup)

//    val jobNameLookupFromSnap = jobsSnapshot.asDF
//      .select('organization_id, (unix_timestamp('Pipeline_SnapTS) * lit(1000)).alias("timestamp"),
//        'job_id.alias("jobId"),
//        $"settings.name".alias("jobName")
//      )

    val jobNameLookupFromStatus = jobsStatus.asDF
      .select('organization_id, 'timestamp, 'jobId, 'jobName
      )

    // jobNames from MLFlow Runs
    val experimentName = get_json_object($"taskDetail.notebook_task", "$.base_parameters.EXPERIMENT_NAME")

    val jobRunsWIdsAndNames = jobRunsBaseWIDs
      .toTSDF("timestamp", "organization_id", "jobId")
//      .lookupWhen(
//        jobNameLookupFromAudit.toTSDF("timestamp", "organization_id", "jobId")
//      )
      .lookupWhen(
        jobNameLookupFromStatus.toTSDF("timestamp", "organization_id", "jobId")
    ).df
      .withColumn("jobName", when('jobName.isNull && experimentName.isNotNull, experimentName).otherwise('jobName))
      .withColumn("jobName", when('jobName.isNull && 'run_name.isNotNull, 'run_name).otherwise('jobName))

    /**
     * Child job run times are double counted when kept at the top level with their parents, thus this step is to
     * nest the children under the parent. Currently, only nesting one layer.
     */
    val jobRunsWJobMetaWithoutChildren = jobRunsWIdsAndNames
      .filter(get_json_object('workflow_context, "$.root_run_id").isNull)

    val jobRunsWJobMetaWithoutChildrenInteractive = jobRunsWJobMetaWithoutChildren.filter('jobClusterType === "existing")
    val jobRunsWJobMetaWithoutChildrenAutomated = jobRunsWJobMetaWithoutChildren.filter('jobClusterType === "new")

    // clusters -- get clusterID by derived name AND/OR clusterName by clusterID
    val (interactiveRunsWJobClusterMeta, automatedRunsWJobClusterMeta) = if (clusterLookups.nonEmpty) {

      var interactiveClusterMetaLookupBuilder = jobRunsWJobMetaWithoutChildrenInteractive
      var automatedClusterMetaLookupBuilder = jobRunsWJobMetaWithoutChildrenAutomated

      if (clusterLookups.contains("clusterSpecLookup")) {
        // GET cluster_name by clusterID for interactive clusters
        interactiveClusterMetaLookupBuilder = interactiveClusterMetaLookupBuilder
          .toTSDF("timestamp", "organization_id", "clusterId")
          .lookupWhen(
            clusterLookups("clusterSpecLookup")
              .toTSDF("timestamp", "organization_id", "clusterId"),
            tsPartitionVal = 64
          ).df

        // get cluster_id by cluster_name (derived)
        automatedClusterMetaLookupBuilder = automatedClusterMetaLookupBuilder
          .toTSDF("timestamp", "organization_id", "cluster_name")
          .lookupWhen(
            clusterLookups("clusterSpecLookup")
              .toTSDF("timestamp", "organization_id", "cluster_name")
          ).df
      }

      // look in run snapshots when values still null attempt to fill them from the snapshots
      if (clusterLookups.contains("clusterSnapLookup")) {
        interactiveClusterMetaLookupBuilder = interactiveClusterMetaLookupBuilder
          .toTSDF("timestamp", "organization_id", "clusterId")
          .lookupWhen(
            clusterLookups("clusterSnapLookup")
              .toTSDF("timestamp", "organization_id", "clusterId"),
            tsPartitionVal = 64
          ).df

        automatedClusterMetaLookupBuilder = automatedClusterMetaLookupBuilder
          .toTSDF("timestamp", "organization_id", "cluster_name")
          .lookupWhen(
            clusterLookups("clusterSnapLookup")
              .toTSDF("timestamp", "organization_id", "cluster_name")
          ).df
      }

      (interactiveClusterMetaLookupBuilder, automatedClusterMetaLookupBuilder)
    } else (jobRunsWJobMetaWithoutChildrenInteractive, jobRunsWJobMetaWithoutChildrenAutomated)

    // Union the interactive and automated back together with the cluster ids and names
    val jobRunsWMeta = interactiveRunsWJobClusterMeta.unionByName(automatedRunsWJobClusterMeta)

    // Get Ephemeral Notebook Job Run Details to be nested into the parent job runs
    val childJobRuns = jobRunsWIdsAndNames
      .filter(get_json_object('workflow_context, "$.root_run_id").isNotNull)

    val childRunsForNesting = childJobRuns
      .withColumn("child", struct(childJobRuns.schema.fieldNames map col: _*))
      .select(
        get_json_object('workflow_context, "$.root_run_id").cast("long").alias("runId"), 'child
      )
      .groupBy('runId)
      .agg(collect_list('child).alias("children"))

    val jobRunsFinal = jobRunsWMeta
      .join(childRunsForNesting, Seq("runId"), "left")
      .drop("timestamp") // duplicated to enable asOf Lookups, dropping to clean up
      .withColumn("endEpochMS", $"jobRunTime.endEpochMS") // for incremental downstream after job termination
      .withColumn("runId", 'runId.cast("long"))
      .withColumn("jobId", 'jobId.cast("long"))
      .withColumn("idInJob", 'idInJob.cast("long"))

    jobsAuditComplete.unpersist()

    jobRunsFinal
  }

  protected def notebookSummary()(df: DataFrame): DataFrame = {
    val notebookCols = auditBaseCols ++ Array[Column]('notebookId, 'notebookName, 'path, 'oldName, 'oldPath,
      'newName, 'newPath, 'parentPath, 'clusterId)

    df.filter('serviceName === "notebook")
      .selectExpr("*", "requestParams.*").drop("requestParams", "Overwatch_RunID")
      .withColumn("pathLength", when('notebookName.isNull, size(split('path, "/"))))
      .withColumn("notebookName", when('notebookName.isNull, split('path, "/")('pathLength - 1)).otherwise('notebookName))
      .select(notebookCols: _*)
  }

}
