## MVC 패턴

### 등장 배경

* 너무 많은 역할
  * 비즈니스 로직 + 뷰 렌더링
  * 유지보수 어려워짐
* 변경의 라이프 사이클
  * 둘 사이의 변경의 라이프 사이클이 다름
  * UI와 비즈니스 로직을 수정하는 일은 다르게 발생할 가능성이 매우 높고
  * 서로에게 영향을 주지 않음
  * 유지보수하기 좋지 않다
* 기능 특화
  * JSP 같은 뷰 템플릿은 화면을 렌더링하는데 최적화 되어 있음

### MVC(Model View Controller)

MVC 패턴은 지금까지 학습한 것처럼 하나의 서블릿이나, JSP로 처리하던 것을 컨트롤러와 뷰라는 영역으로 서로 역할을 나눈 것을 말한다.

* 컨트롤러
  * HTTP요청을 받아서 파라미터를 검증하고, 비즈니스 로직을 실행한다.
  * 뷰에 전달할 결과 데이터를 조회해서 모델에 담는다.
* 모델
  * 뷰에 출력할 데이터를 담아둔다.
  * 뷰가 필요한 데이터를 모두 모델에 담아서 전달해주는 덕분에 뷰는 비즈니스 로직이나 데이터 접근을 몰라도 되고, 화면을 렌더링하는 일에 집중할 수 있다.
* 뷰
  * 모델에 담겨있는 데이터를 사용해서 화면을 그리는 일에 집중한다. 여기서 HTML을 생성하는 부분을 말한다.

#### 회원 등록 폼

컨트롤러

```java
@WebServlet(name = "mvcMemberFormServlet", urlPatterns = "/servlet-mvc/members/new-form")
 public class MvcMemberFormServlet extends HttpServlet {
 	@Override
 	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
 	String viewPath = "/WEB-INF/views/new-form.jsp";
 	RequestDispatcher dispatcher = request.getRequestDispatcher(viewPath);
        dispatcher.forward(request, response);
 	}
}
```

RequestDispatcher는 지정된 경로에 위치한 jsp를 래핑한다. 이 객체의 forward 메서드를 통해서 jsp를 호출하여 요청과 응답의 제어권을 넘길 수 있다. 

WEB-INF 경로의 jsp는 외부에서 직접 호출할 수 없다. forward를 사용하면 호출이 가능해진다.

#### 회원 저장

```java
request.setAttribute("member", member);
```

회원 저장은 setAttribute()를 사용해서 request 객체에 보관한 뒤 뷰에 전달하면 된다.

```jsp
<li>id=<%=((Member)request.getAttribute("member")).getId()%></li>
```

뷰에서 위처럼 getAttribute를 사용해서 꺼내 쓰면 되지만 너무 복잡해진다.

```jsp
<li>id=${member.id}</li>
```

${}문법으로 request의 attribute에 담긴 데이터를 편리하게 조회할 수 있다.

#### 회원 목록

```java
request.setAttribute("members", members);
```

이렇게 목록을 저장한 뒤

```jsp
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
```

이렇게 선언하면

```jsp
<c:forEach var="item" items="${members}">
        <tr>
            <td>${item.id}</td>
            <td>${item.username}</td>
            <td>${item.age}</td>
        </tr>
</c:forEach>
```

깔끔하게 목록을 조회할 수 있다.

JSP와 같은 뷰 템플릿은 이렇게 화면을 렌더링 하는데 특화된 다양한 기능을 제공한다.

MVC 덕분에 컨트롤러 로직과 뷰 로직을 확실하게 분리할 수 있다.

### 한계

컨트롤러는 딱 봐도 중복이 많고, 필요하지 않는 코드들도 많이 보인다.

#### 포워드 중복

```java
 RequestDispatcher dispatcher = request.getRequestDispatcher(viewPath);
 dispatcher.forward(request, response);
```

#### ViewPath에 중복

```java
 String viewPath = "/WEB-INF/views/new-form.jsp";
```

* prefix: /WEB-INF/views/
* suffix: .jsp

그리고 만약 jsp가 아닌 thymeleaf 같은 다른 뷰로 변경한다면 전체 코드를 다 변경해야 한다.

#### 사용하지 않는 코드

다음 코드를 사용할 때도 있고, 사용하지 않을 때도 있다.

```java
HttpServletRequest request, HttpServletResponse response
```

그리고 이 코드를 사용하는 코드는 테스트 케이스를 작성하기도 어렵다.

#### 공통 처리가 어렵다

이 문제를 해결하려면 컨트롤러 호출 전에 먼저 공통 기능을 처리해야 한다. 프론트 컨트롤러(Front Controller) 패턴을 도입하면 깔끔하게 해결할 수 있다.  