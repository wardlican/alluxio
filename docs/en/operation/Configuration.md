---
layout: global
title: Configuration Settings
---


An Alluxio cluster can be configured by setting the values of Alluxio
[configuration properties]({{ '/en/reference/Properties-List.html' | relativize_url }}) within
`${ALLUXIO_HOME}/conf/alluxio-site.properties`.

The two major components to configure are:
- [Alluxio servers](#configure-an-alluxio-cluster), consisting of Alluxio processes such as masters and workers
- [Alluxio clients](#configure-applications), which are typically a part of compute applications

Alluxio properties mostly fall into three categories:

- properties prefixed with `alluxio.user` affect Alluxio client operations (e.g. compute applications)
- properties prefixed with `alluxio.master` affect the Alluxio master processes
- properties prefixed with `alluxio.worker` affect the Alluxio worker processes

## Configure Applications

Customizing how an application interacts with Alluxio is specific to each application.
The following are recommendations for some common applications.

### Alluxio Shell Commands

Alluxio shell users can put JVM system properties `-Dproperty=value` after the `fs` command and
before the subcommand to specify Alluxio user properties from the command line.
For example, the following Alluxio shell command sets the write type to `CACHE_THROUGH` when copying
files to Alluxio:

```shell
$ ./bin/alluxio fs -Dalluxio.user.file.writetype.default=CACHE_THROUGH \
    copyFromLocal README.md /README.md
```

Note that, as a part of Alluxio deployment, the Alluxio shell will also take the configuration in
`${ALLUXIO_HOME}/conf/alluxio-site.properties` when it is run from Alluxio installation at
`${ALLUXIO_HOME}`.

### Spark

To customize Alluxio client-side properties in Spark applications,
Spark users can use pass Alluxio properties as JVM system properties.
See examples for
[configuring the Spark service]({{ '/en/compute/Spark.html' | relativize_url }}#basic-setup)
or for
[individual Spark jobs]({{ '/en/compute/Spark.html' | relativize_url }}#customize-alluxio-user-properties-for-individual-spark-jobs).

### Hadoop MapReduce

See examples to configure Alluxio properties for
[the MapReduce service]({{ '/en/compute/Hadoop-MapReduce.html' | relativize_url }}#customize-alluxio-user-properties-for-all-mapreduce-jobs)
or for
[individual MapReduce jobs]({{ '/en/compute/Hadoop-MapReduce.html' | relativize_url }}#customize-alluxio-user-properties-for-individual-mapreduce-jobs).

### Hive

Hive can be configured to use customized Alluxio client-side properties for the entire service.
See
[examples]({{ '/en/compute/Hive.html' | relativize_url }}#customize-alluxio-user-properties).

### Presto

Presto can be configured to use customized Alluxio client-side properties for the entire service.
See
[examples]({{ '/en/compute/Presto.html' | relativize_url }}#customize-alluxio-user-properties).

## Configure an Alluxio Cluster

### `alluxio-site.properties` Files (Recommended)

Alluxio admins can create and edit the properties file `conf/alluxio-site.properties` to
configure Alluxio masters or workers.
If this file does not exist, it can be copied from the template file under `${ALLUXIO_HOME}/conf`:

```shell
$ cp conf/alluxio-site.properties.template conf/alluxio-site.properties
```

Make sure that this file is distributed to `${ALLUXIO_HOME}/conf` on every Alluxio master
and worker before starting the cluster.
Restarting Alluxio processes is the safest way to ensure any configuration updates are applied.

### Environment Variables

Alluxio supports defining a few frequently used configuration settings through environment
variables, including:

<table class="table table-striped">
<tr><th>Environment Variable</th><th>Description</th></tr>
{% for env_var in site.data.table.en.env_vars %}
  <tr>
    <td markdown="span">`{{env_var.name}}`</td>
    <td markdown="span">{{env_var.description}}</td>
  </tr>
{% endfor %}
</table>

For example, the following example will set up:
- an Alluxio master at `localhost`
- the root mount point as an HDFS cluster with a namenode also running at `localhost`
- defines the maximum heap space of the VM to be 30g
- enable Java remote debugging at port 7001

```shell
$ export ALLUXIO_MASTER_HOSTNAME="localhost"
$ export ALLUXIO_MASTER_MOUNT_TABLE_ROOT_UFS="hdfs://localhost:9000"
$ export ALLUXIO_MASTER_JAVA_OPTS="-Xmx30g"
$ export ALLUXIO_MASTER_ATTACH_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=7001"
```

Users can either set these variables through the shell or in `conf/alluxio-env.sh`.
If this file does not exist yet, it can be copied from the template file under `${ALLUXIO_HOME}/conf`:

```shell
$ cp conf/alluxio-env.sh.template conf/alluxio-env.sh
```

### Cluster Defaults

When different client applications (Alluxio Shell CLI, Spark jobs, MapReduce jobs)
or Alluxio workers connect to an Alluxio master, they will initialize their own Alluxio
configuration properties with the default values supplied by the masters based on the master-side
`${ALLUXIO_HOME}/conf/alluxio-site.properties` files.
As a result, cluster admins can set default client-side settings (e.g., `alluxio.user.*`), or
network transport settings (e.g., `alluxio.security.authentication.type`) in
`${ALLUXIO_HOME}/conf/alluxio-site.properties` on all the masters, which will be distributed and
become cluster-wide default values when clients and workers connect.

For example, the property `alluxio.user.file.writetype.default` defaults to `ASYNC_THROUGH`, which
first writes to Alluxio and then asynchronously writes to the UFS.
In an Alluxio cluster where data persistence is preferred and all jobs need to write to both the UFS
and Alluxio, the administrator can add `alluxio.user.file.writetype.default=CACHE_THROUGH` in each
master's `alluxio-site.properties` file.
After restarting the cluster, all jobs will automatically set `alluxio.user.file.writetype.default`
to `CACHE_THROUGH`.

Clients can ignore or overwrite the cluster-wide default values by following the approaches
described in [Configure Applications](#configure-applications) to overwrite the same properties.

### Path Defaults

Alluxio administrators can set default client-side configurations for specific
Alluxio filesystem paths.
Filesystem client operations have options which are derived from client side configuration
properties.
Only client-side configuration properties can be set as path defaults.

For example, the `createFile` operation has an option to specify write type.
By default, the write type is the value of the configuration key `alluxio.user.file.writetype.default`.
The administrator can set default value of `alluxio.user.file.write.type.default` to `MUST_CACHE`
for all paths with prefix `/tmp` by running:

```shell
$ bin/alluxio fsadmin pathConf add --property alluxio.user.file.writetype.default=MUST_CACHE /tmp`
```

After executing this command any create operations on paths with prefix `/tmp` will use the
`MUST_CACHE` write type by default unless the application configuration overrides the cluster
defaults.

Path defaults will be automatically propagated to long-running clients if they are updated.
If the administrator updates path defaults using the following:

```shell
$ bin/alluxio fsadmin pathConf add --property alluxio.user.file.writetype.default=THROUGH /tmp
```

All write operations that occur on a path with the prefix `/tmp` prefix will use
the `THROUGH` write type by default.

## Configuration Sources

Alluxio properties can be configured from multiple sources.
A property's final value is determined by the following priority list, from highest priority to lowest:

1. [JVM system properties (i.e., `-Dproperty=key`)](http://docs.oracle.com/javase/jndi/tutorial/beyond/env/source.html#SYS)
2. [Environment variables](#environment-variables)
3. [Property files](#alluxio-siteproperties-files-recommended):
When an Alluxio cluster starts, each server process including master and worker searches for
`alluxio-site.properties` within the following directories in the given order, stopping when a match is found:
`${CLASSPATH}`, `${HOME}/.alluxio/`, `/etc/alluxio/`, and `${ALLUXIO_HOME}/conf`
4. [Path default values](#path-defaults)
5. [Cluster default values](#cluster-defaults):
An Alluxio client may initialize its configuration based on the cluster-wide default configuration served by the masters.

If no user-specified configuration is found for a property, Alluxio will fall back to
its [default property value]({{ '/en/reference/Properties-List.html' | relativize_url }}).

To check the value of a specific configuration property and the source of its value,
users can run the following command:

```shell
$ ./bin/alluxio getConf alluxio.worker.rpc.port
29998
$ ./bin/alluxio getConf --source alluxio.worker.rpc.port
DEFAULT
```

To list all of the configuration properties with sources:

```shell
$ ./bin/alluxio getConf --source
alluxio.conf.dir=/Users/bob/alluxio/conf (SYSTEM_PROPERTY)
alluxio.debug=false (DEFAULT)
...
```

Users can also specify the `--master` option to list all
of the cluster-wide configuration properties served by the masters.
Note that with the `--master` option, `getConf` will query the
master which requires the master process to be running.
Otherwise, without `--master` option, this command only checks the local configuration.

```shell
$ ./bin/alluxio getConf --master --source
alluxio.conf.dir=/Users/bob/alluxio/conf (SYSTEM_PROPERTY)
alluxio.debug=false (DEFAULT)
...
```

## Java 11 Configuration

Alluxio also supports Java 11.
To run Alluxio on Java 11, configure the `JAVA_HOME` environment variable to point to a Java 11
installation directory.
If you only want to use Java 11 for Alluxio, you can set the `JAVA_HOME` environment variable in
the `alluxio-env.sh` file.
Setting the `JAVA_HOME` in `alluxio-env.sh` will not affect the Java version which may be used
by other applications running in the same environment.

## Server Configuration Checker

The server-side configuration checker helps discover configuration errors and warnings.
Suspected configuration errors are reported through the web UI, `fsadmin doctor` CLI, and master logs.

The web UI shows the result of the server configuration check.

![webUi]({{ '/img/screenshot_configuration_checker_webui.png' | relativize_url }})

Users can also run the `fsadmin doctor` command to get the same results.

```shell
$ ./bin/alluxio fsadmin doctor configuration
```

Configuration warnings can also be found in the master logs.

![masterLogs]({{ '/img/screenshot_configuration_checker_masterlogs.png' | relativize_url }})
