# DI-container

Минималистичный DI-контейнер на **Java 21** с конфигурацией в **EDN** (clojure, data-only) и поддержкой аннотаций (`@Inject`, `@Named`, `Provider<T>`). Проект предназначен для демонстрации и изучения механизмов DI: создание объектов, разрешение зависимостей, scope’ы, валидация конфигурации и диагностика.

---

## Возможности

### DI по имени/типу (API `di.api.DiContainer`)
- `Object getBean(String name)` — получение бина по имени.
- `<T> T getBean(Class<T> type)` — получение бина по типу (строго: 0 кандидатов → ошибка, >1 → ambiguous).
- `<T> T getBean(Class<T> type, String name)` — получение по имени с проверкой совместимости типов.

Ключевая реализация: `di.core.SimpleDiContainer`.

### Scope’ы
Поддерживаются `di.model.Scope`:
- `SINGLETON` — один экземпляр на контейнер (кеширование).
- `PROTOTYPE` — новый экземпляр на каждый `getBean(...)`.
- `THREAD` — один экземпляр на поток (через `ThreadLocal`).

### Инъекции

#### Конфиг-инъекции (EDN)
Через `BeanDefinition` (`di.model.*`) из EDN-конфига:
- **constructor args**: `:constructor-args` с аргументами по `:index`.
- **method injections**: `:methods` + `:args` с аргументами по `:index`.

#### `@Inject` / `@Named`
`SimpleDiContainer` умеет:
- выбирать конструктор по правилам `@Inject`;
- выполнять `@Inject` инъекцию в поля (включая поля суперклассов);
- выполнять `@Inject` инъекцию в методы;
- учитывать `@Named` при разрешении зависимостей.

#### `Provider<T>`
- поддерживается `javax.inject.Provider<T>` как injection point;
- контейнер извлекает `T` из generic-сигнатуры `Provider<T>`;
- с `@Named` провайдер возвращает бин по имени, иначе — по типу.

### `validate()` (fail-fast)
`di.core.SimpleDiContainer#validate()` выполняет fail-fast проверку конфигурации без создания бинов, включая:
- существование ссылок `(ref "...")`;
- корректность выбора ctor/method по arity для конфиг-инъекций;
- полноту индексов `0..N-1`;
- проверку конвертации литералов через `di.core.ValueConverter`;
- ограничение для singleton → thread (через config-ref): прокси возможен только при инъекции в **интерфейс**.

### `getDependencyGraph()` (best-effort)
`DiContainer#getDependencyGraph()` строит граф зависимостей:
- точные ребра из config `RefValue` (включая вложенные list/map);
- best-effort зависимости из `@Inject` (включая `Provider<T>`; некорректные `Provider` не должны ломать построение графа).

### Thread-scoped proxy (singleton → thread через интерфейс)
Если singleton зависит от thread-scoped бина, контейнер может создать прокси:
- прокси создаётся **только** для интерфейса (иначе ошибка);
- реализация: `di.proxy.ClojureJavaProxyFactory` (генерация Java source на Clojure + компиляция через `di.proxy.InMemoryJavaCompiler` + кеширование классов).

---

## Быстрый старт

### Требования
- **JDK 21** (важно: для компиляции прокси нужен полноценный JDK, а не JRE)
- **Maven**

### Команды
Запуск всех тестов:
```bash
mvn test
```

Запуск конкретного класса тестов (пример):
```bash
mvn -q test -Dtest=DiEdnIntegrationTest
```

### Загрузка конфига и создание контейнера

Минимальный пример (загрузка EDN из `src/main/resources`):

```java
import di.api.DiContainer;
import di.config.EdnConfigLoader;
import di.core.SimpleDiContainer;
import di.model.BeanDefinition;

import java.util.List;

public class Example {
    public static void main(String[] args) {
        List<BeanDefinition> definitions = new EdnConfigLoader().loadFromResource("di.edn");

        DiContainer container = new SimpleDiContainer(definitions);

        // Fail-fast проверка (рекомендуется делать на старте)
        ((SimpleDiContainer) container).validate();

        Object foo = container.getBean("fooSingleton");
        System.out.println(foo);
    }
}
```

> `EdnConfigLoader.loadFromResource(...)` кидает `di.config.EdnValidationException`, если ресурс не найден или конфиг невалиден.

---

## Формат EDN-конфига

### Корневая структура
Конфиг — это map вида:
```clojure
{:beans [ ... ]}
```

### Обязательные ключи bean-map
В каждом элементе `:beans` обязательны:
- `:name` — строка (имя бина),
- `:class` — строка (FQCN класса, например `"demo.Foo"`),
- `:scope` — keyword: `:singleton` / `:prototype` / `:thread`.

Также поддерживаются (опционально):
- `:constructor-args` — vector аргументов конструктора,
- `:methods` — vector описаний method injections.

> В `di.config.EdnConfigLoader` включён whitelist ключей: неизвестные ключи в bean-map приводят к `EdnValidationException`.

### `:constructor-args` и `:methods`: что такое `:index` и правила
**Аргумент** задаётся как map:
```clojure
{:index 0 :arg (value "...")}
```

Правила:
- `index >= 0`,
- **без дублей** `:index` в пределах одного списка аргументов,
- должны быть определены **все индексы** `0..N-1` (контейнер проверяет это в `SimpleDiContainer#validate()`).

### Формы значений

#### `(ref "beanName")`
Ссылка на другой бин:
- принимает **только строку** (иначе `EdnValidationException`).

#### `(value ...)`
Литерал или коллекция:
- scalar: `string/number/boolean/char/null/...` → `LiteralValue`,
- vector внутри `(value ...)` → `ListValue`,
- map внутри `(value ...)` → `MapValue`.

Вложенные `(value ...)` / `(ref ...)` внутри list/map **допустимы**.

##### Map-ключи
В `(value { ... })` ключи могут быть:
- `string` (`"k"`),
- `keyword` (`:k`) — нормализуется к строке по имени keyword (например `:a` → `"a"`).

После нормализации ключей дубликаты запрещены (иначе `EdnValidationException`).

---

### Примеры (минимальные, рабочие)

#### 1) Простой бин
```clojure
{:beans
 [{:name "fooSingleton"
   :class "demo.Foo"
   :scope :singleton}]}
```

#### 2) Бин с constructor args + method injections
```clojure
{:beans
 [{:name "person"
   :class "demo.Person"
   :scope :prototype
   :constructor-args
   [{:index 0 :arg (value "Alice")}
    {:index 1 :arg (value "30")}]     ;; String -> int через di.core.ValueConverter
   :methods
   [{:name "setActive" :args [{:index 0 :arg (value true)}]}
    {:name "setScore"  :args [{:index 0 :arg (value 12)}]}]}]}
```

#### 3) Коллекции + вложенный `(ref ...)` (по мотивам `di.edn`)
```clojure
{:beans
 [{:name "fooSingleton"
   :class "demo.Foo"
   :scope :singleton}

  {:name "collectionsDemo"
   :class "demo.ConfigCollectionsDemo"
   :scope :singleton
   :constructor-args
   [{:index 0
     :arg (value {:title "Demo"
                  :numbers (value [1 2 3])
                  :nested (value {:k1 "v1"
                                 :k2 (value [10 20 (ref "fooSingleton")])})})}]}]}
```

---

## Поведение и контракты контейнера

### Что считается ошибкой (тип исключения)

- **Бин не найден по имени** (`getBean(String)` / `getBean(type,name)`):
  - `NoSuchElementException`

- **По типу нет кандидатов** (`getBean(Class)`):
  - `NoSuchElementException`

- **Ambiguous по типу** (`getBean(Class)` / разрешение по типу в `@Inject`):
  - `IllegalStateException`

- **`getBean(type,name)` несовместим с типом**:
  - `ClassCastException`

- **Неправильный выбор конструктора/метода (arity/overload)** при конфиг-инъекциях:
  - `IllegalStateException` (например: “No constructor with N parameter(s) …”, “Ambiguous setter overload …”)

- **Циклическая зависимость** при создании:
  - `IllegalStateException` (со строкой вида “Cyclic dependency detected: A -> B -> A”)

- **Неконкретный `Provider<T>`** (raw/`Provider<?>` и т.п.):
  - `IllegalStateException`

### “Resolution path …” в сообщениях
При ошибках создания контейнер добавляет диагностическую часть:
- `Resolution path: beanA -> beanB -> beanC`

Пример (схематично):
```text
No beans found for type: demo.Greeter Resolution path: welcomeService -> ...
```

---

## Thread-scoped proxy (важный раздел)

### Проблема: singleton → thread
Если singleton напрямую получает thread-scoped бин как обычную зависимость, он “захватит” конкретный экземпляр из одного потока — и семантика `Scope.THREAD` будет нарушена.

### Правило
Прокси возможен **только через интерфейс**:
- если thread-scoped зависимость внедряется в singleton и тип injection point **не интерфейс** → контейнер кидает `IllegalStateException`.

### Как это работает в проекте
- `di.proxy.ClojureJavaProxyFactory` реализует `di.proxy.ScopedProxyFactory` и создаёт прокси:
  - генерирует Java source на Clojure (`di.proxy-generator`),
  - компилирует его в памяти через `di.proxy.InMemoryJavaCompiler` (используется `ToolProvider.getSystemJavaCompiler()`),
  - кеширует скомпилированные proxy-классы.

### Как выглядит для пользователя контейнера (псевдокод)
```java
interface RequestIdProvider {
    String getRequestId();
}

// thread-scoped bean: "threadRequestId" (class demo.ThreadRequestIdProvider)
RequestIdProvider dep = container.getBean(RequestIdProvider.class, "threadRequestId");

// singleton consumer получает не сам thread-bean, а proxy (implements RequestIdProvider)
RequestIdProvider proxy = container.getBean(RequestIdProvider.class /* injection type */);

// каждый вызов proxy.getRequestId() делегирует в container.getBean("threadRequestId")
// и получает корректный экземпляр из текущего потока
```

---

## Готовые сценарии в `src/main/resources`

Файлы конфигов (реальные имена) и ожидаемое поведение:

| Файл | Что демонстрирует | Ожидаемое поведение |
|---|---|---|
| `di.edn` | Happy-path: scope’ы, `@Named`, `Provider<T>`, конфиг-инъекции, коллекции/вложенные `(ref ...)` | Успех (load/validate/getBean) |
| `di-cycle.edn` | Циклические зависимости (demo.CycleA/B/C) | Ошибка при создании: `IllegalStateException` (“Cyclic dependency detected …”) |
| `di-not-found.edn` | `(ref "noSuchBean")` на несуществующий бин | Ошибка в `validate()`/создании: `NoSuchElementException` |
| `di-ambiguous.edn` | Неоднозначность по типу (несколько кандидатов) | Ошибка при разрешении: `IllegalStateException` (ambiguous) |
| `di-overload.edn` | Перегрузка метода `setX` (name+arity ambiguous) | Ошибка в `validate()`: `IllegalStateException` (“Ambiguous setter overload …”) |
| `di-bad-conversion.edn` | Невалидная конвертация литерала (`"not-a-number"` → число) | Ошибка в `validate()`: `IllegalStateException` (причина часто `NumberFormatException`) |
| `di-thread-proxy-good.edn` | singleton → thread через интерфейс (прокси допустим) | Успех (контейнер генерирует proxy) |
| `di-thread-proxy-bad.edn` | singleton → thread без интерфейса (прокси невозможно) | Ошибка: `IllegalStateException` (“requires proxy, but injection type is not an interface …”) |
| `di-bad-form.edn` | Неверная форма `(ref ...)` (например `(ref 123)`) | Ошибка загрузки: `EdnValidationException` |

---

## Ограничения и заметки

- `@Inject` method injections выполняются через `Class#getMethods()`, то есть учитываются **только public методы**.
- `SINGLETON` кеш (`singletonCache`) допускает гонку: при параллельном старте два потока могут создать два экземпляра, но в кеш будет опубликован один (через `putIfAbsent`).
- `getDependencyGraph()` — **best-effort** и не гарантирует полноту графа (особенно для сложных/некорректных сигнатур `Provider<T>`); метод не должен падать из‑за “плохого Provider”.
