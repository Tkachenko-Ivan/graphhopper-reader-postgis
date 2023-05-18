# Описание

Здесь представлен пример того, как переопределить источник данных в [GraphHopper](https://www.graphhopper.com/) для построения автомобильного маршрута.

* В качестве источника данных используется БД PostgreSQL с установленным расширением PostGIS. Готовую базу, заполненную данными, можно взять из Docker контейнера [tkachenkoivan/road-data](https://hub.docker.com/r/tkachenkoivan/road-data).
* Подробное описание как это работает есть в публикации на Хабре: [Как хранить сеть дорог в БД для построения маршрута?](https://habr.com/ru/articles/688556/).
* Там же, в публикации, описано [как дополнять базу новыми данными](https://habr.com/ru/articles/688556/#NewData), пример кода есть на GitHub Gist: [PostGisGeometry.java](https://gist.github.com/Tkachenko-Ivan/c2418a09c887e0baa0a823944d76e343).
* Примеры загружаемых данных, на которых можно протестировать загрузку есть на GitHub: [Tkachenko-Ivan/shape-example-graphhopper](https://github.com/Tkachenko-Ivan/shape-example-graphhopper).

# Назначение репозитория

Этот репозиторий - fork репозитория [mbasa/graphhopper-reader-postgis](https://github.com/mbasa/graphhopper-reader-postgis), однако он не сделан как fork, а склонирован и выложен заново как отдельный репозиторий. 

Сделанно это сознательно, т.к. я внёс в него некоторые изменения, так сказать, "для себя", которые я не планирую контрибьютить в базовый репозиторий, 
а вот второй форк, вот он: [Tkachenko-Ivan/graphhopper-reader-postgis-fork]([Tkachenko-Ivan/graphhopper-reader-postgis-fork](https://github.com/Tkachenko-Ivan/graphhopper-reader-postgis-fork)), в него я как раз планирую добавлять изменения для создания Pull Request.
К сожалению создавать два форка на одном аккаунте github нельзя, и создавать второй аккаунт я не хочу, поэтому пошёл на такие ухищрения.

# Работа с примером

В классе [Worker](https://github.com/Tkachenko-Ivan/graphhopper-reader-postgis/blob/master/src/main/java/com/graphhopper/Worker.java), установите настройки подключения к своей базе данных:

```Java 
GraphHopperConfig graphHopperConfig = new GraphHopperConfig();
graphHopperConfig.putObject("db.host", "localhost");
graphHopperConfig.putObject("db.port", "5432");
graphHopperConfig.putObject("db.database", "postgres");
graphHopperConfig.putObject("db.schema", "public");
graphHopperConfig.putObject("db.user", "postgres");
graphHopperConfig.putObject("db.passwd", "RoutePass");

graphHopperConfig.putObject("db.tags_to_copy", "name");
graphHopperConfig.putObject("datareader.file", "roads_view");
```

Здесь `roads_view` - это таблица или представление с дрогами.

Укажите директорию, в которую будет сохранятся построенный граф дорог:

```Java
private static final String dir = "D:\\Test\\graph";
```

Создание нового графа дорог, или загрузка существующего из директории:

```Java
GraphHopper graphHopper = new GraphHopperPostgis().forServer();
 
graphHopper.init(graphHopperConfig);
graphHopper.importOrLoad();
```

Сформируйте запрос на построение маршрута:

```Java
GHRequest request = new GHRequest();
request.addPoint(new GHPoint(54.42792, 19.88905));
request.addPoint(new GHPoint(54.43032, 19.892));
request.setProfile("my_car");
```

Ответ, т.е. построенный маршрут:

```Java
GHResponse response = graphHopper.route(request);
```

## Смотри также

* [graphhopper-reader-postgis/README.md](https://github.com/mbasa/graphhopper-reader-postgis/blob/master/README.md)
